package dev.jharness.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.agent.Session;
import dev.jharness.agent.TurnResult;
import dev.jharness.config.ModelPrice;
import dev.jharness.config.Settings;
import dev.jharness.events.Event;
import dev.jharness.events.Json;
import dev.jharness.llm.ReplayProvider;
import dev.jharness.security.Prompter;

/** Spec 049: tokens and cost added up from model.completed, in /usage, jharness usage and the headless result. */
class UsageTest {

    @TempDir
    Path tmp;

    static final ModelPrice PRICE = new ModelPrice(3, 15, 0.3, 3.75);

    /** A reply with the token counts a real model would report. */
    static com.fasterxml.jackson.databind.JsonNode reply(String text, int in, int out, int read) {
        return Json.object().put("reply", text).put("inputTokens", in).put("outputTokens", out).put("cacheReadTokens", read);
    }

    private Path session(Path home, Path ws, ModelPrice price, String... replies) throws Exception {
        List<com.fasterxml.jackson.databind.JsonNode> lines = new java.util.ArrayList<>();
        for (String r : replies) {
            lines.add(reply(r, 1_000_000, 100_000, 400_000));
        }
        try (Session s = Session.open(home, ws, Settings.defaults(), Session.Access.prompter(op -> Prompter.Answer.ONCE),
                (bus, turns) -> new ReplayProvider(lines, bus, turns).price(price).contextWindow(10_000_000))) {
            for (int i = 0; i < replies.length; i++) {
                TurnResult r = s.turn("go " + i, d -> {});
                assertEquals("done", r.stopReason());
            }
            return s.logPath();
        }
    }

    record Run(int code, String out, String err) {}

    private Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Main m = new Main();
        m.out = new PrintStream(out, true, StandardCharsets.UTF_8);
        m.err = new PrintStream(err, true, StandardCharsets.UTF_8);
        int code = Main.run(m, args);
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /** AC-03: the log adds up by agent; an unpriced request is counted apart. */
    @Test
    void aLogAddsUpByAgent() throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path log = session(home, ws, PRICE, "One.", "Two.");
        // A subagent's request from a profile with no price, appended as a logged event would be.
        Files.writeString(log, "{\"type\":\"model.completed\",\"agent\":\"sub-1\",\"inputTokens\":10,\"outputTokens\":5}\n",
                java.nio.file.StandardOpenOption.APPEND);
        Usage u = Usage.ofLog(log);
        assertEquals(3, u.total.requests);
        assertEquals(2_000_010, u.total.input);
        assertEquals(800_000, u.total.cacheRead);
        // Each priced request: 600,000 fresh at $3, 400,000 cached at $0.30, 100,000 out at $15.
        double each = (600_000 * 3 + 400_000 * 0.3 + 100_000 * 15) / 1e6;
        assertEquals(2 * each, u.total.cost, 1e-9);
        assertEquals(1, u.total.unpriced);
        assertEquals(List.of("main", "sub-1"), List.copyOf(u.agents.keySet()));
        assertEquals("-", u.agents.get("sub-1").costText());
        assertEquals("$6.84+", u.total.costText(), "some requests had no price");
        List<String> t = Usage.table("AGENT", u.agents, u.total);
        assertEquals("AGENT  REQUESTS  INPUT      OUTPUT   CACHE-READ  CACHE-WRITE  COST", t.get(0));
        assertTrue(t.get(1).startsWith("main   2         2,000,000  200,000  800,000     0            $6.84"), t.get(1));
        assertTrue(t.get(3).startsWith("total  3"), t.toString());
        assertTrue(String.join("\n", Usage.notes(u.total)).contains("1 request has no price: set \"price\""));
        assertEquals("$0.0012", Usage.dollars(0.00123));
        assertEquals("$1,234.50", Usage.dollars(1234.5));
    }

    /** AC-04: jharness usage lists recent sessions with a total, shows one by agent, and prints JSON. */
    @Test
    void usageCommand() throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home2"));
        Path ws = Files.createDirectories(tmp.resolve("ws2"));
        Path a = session(home, ws, PRICE, "A.");
        Thread.sleep(1100);
        session(home, ws, null, "B.");
        Run r = run("usage", "--home", home.toString(), "--cwd", ws.toString());
        assertEquals(0, r.code(), r.err());
        List<String> lines = r.out().lines().toList();
        assertTrue(lines.get(0).startsWith("Usage in " + ws + ", last 30 days"), r.out());
        assertTrue(lines.get(1).matches("SESSION +STARTED +MODEL +REQUESTS +INPUT +OUTPUT +CACHE-READ +CACHE-WRITE +COST"), lines.get(1));
        assertTrue(lines.get(2).endsWith("  -"), "the newer, unpriced one first: " + r.out());
        assertTrue(lines.get(3).endsWith("$3.42"), r.out());
        assertTrue(lines.get(4).startsWith("total") && lines.get(4).endsWith("$3.42+"), r.out());
        assertTrue(r.out().contains("1 request has no price"), r.out());

        String id = a.getFileName().toString().replace(".jsonl", "");
        Run one = run("usage", id, "--home", home.toString(), "--cwd", ws.toString());
        assertTrue(one.out().contains("Usage of " + id) && one.out().contains("main   1"), one.out());

        Run json = run("usage", "--json", "--home", home.toString(), "--cwd", ws.toString());
        var j = Json.parse(json.out().strip());
        assertEquals(2, j.get("sessions").size());
        assertEquals(3.42, j.get("total").get("costUsd").asDouble(), 1e-9);
        assertEquals(1, j.get("total").get("unpricedRequests").asInt());
        assertFalse(j.get("sessions").get(0).get("usage").has("costUsd"), "no cost when nothing was priced");

        assertEquals(2, run("usage", "--days", "0", "--home", home.toString()).code());
        assertTrue(run("usage", "s-nope", "--home", home.toString(), "--cwd", ws.toString()).err().contains("jharness usage:"));
    }

    /** AC-04: --days leaves older sessions out. */
    @Test
    void daysLimitTheList() throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home3"));
        Path ws = Files.createDirectories(tmp.resolve("ws3"));
        session(home, ws, PRICE, "A.");
        UsageCommand c = new UsageCommand();
        Main m = new Main();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        m.out = new PrintStream(out, true, StandardCharsets.UTF_8);
        m.err = new PrintStream(new ByteArrayOutputStream(), true);
        c.parent = m;
        c.home = home;
        c.cwd = ws;
        c.days = 1;
        c.now = Instant.now().plusSeconds(3 * 86_400);
        c.zone = ZoneOffset.UTC;
        assertEquals(0, c.call());
        assertTrue(out.toString().contains("No model requests in the last 1 days"), out.toString());
    }

    /** AC-05: the headless result's usage has the cost and the unpriced count. */
    @Test
    void headlessResultHasTheCost() {
        HeadlessResult h = new HeadlessResult("s-x");
        h.onEvent(new Event("1", Instant.now(), "s-x", "main", 1, "host", "model.completed",
                Json.object().put("inputTokens", 100).put("outputTokens", 10).put("costUsd", 0.25)));
        h.onEvent(new Event("2", Instant.now(), "s-x", "sub-1", 1, "host", "model.completed",
                Json.object().put("inputTokens", 50).put("outputTokens", 5)));
        var u = h.result(new TurnResult("ok", "done", 1, null), "ok", 0).get("usage");
        assertEquals(2, u.get("requests").asInt());
        assertEquals(150, u.get("inputTokens").asInt());
        assertEquals(0.25, u.get("costUsd").asDouble(), 1e-9);
        assertEquals(1, u.get("unpricedRequests").asInt());
    }

    /** AC-06: the status line's cost item, from every agent's priced requests. */
    @Test
    void statusLineCost() {
        StatusState s = new StatusState();
        assertEquals("", s.line(List.of("cost")), "nothing until a request has a price");
        s.apply(new Event("1", Instant.now(), "s", "sub-1", 1, "host", "model.completed", Json.object().put("costUsd", 0.5)));
        s.apply(new Event("2", Instant.now(), "s", "main", 1, "host", "model.completed", Json.object().put("costUsd", 0.75)));
        assertEquals("$1.25", s.line(List.of("cost")));
        assertEquals(1.25, s.snapshot(List.of("cost")).get("costUsd").asDouble(), 1e-9);
    }
}
