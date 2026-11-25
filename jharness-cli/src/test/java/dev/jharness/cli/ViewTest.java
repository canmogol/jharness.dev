package dev.jharness.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.agent.Session;
import dev.jharness.config.Settings;
import dev.jharness.events.Json;
import dev.jharness.llm.ReplayProvider;
import dev.jharness.security.Prompter;
import dev.jharness.web.SessionViewer;

/** Spec 048: {@code jharness view} serves a saved session's log; {@code /view} serves the running session live. */
class ViewTest {

    @TempDir
    Path tmp;
    final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(5)).build();

    @AfterEach
    void tearDown() {
        ViewCommand.started = null;
    }

    private Path session(Path home, Path ws, String prompt, String reply) throws Exception {
        try (Session s = Session.open(home, ws, Settings.defaults(), Session.Access.prompter(op -> Prompter.Answer.ONCE),
                (bus, turns) -> ReplayProvider.ofReplies(List.of(reply), bus, turns))) {
            s.turn(prompt, d -> {});
            return s.logPath();
        }
    }

    private static List<String> events(HttpClient http, SessionViewer v, int want) throws Exception {
        var res = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + v.port() + "/events?t=" + v.url().split("t=")[1])).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        List<String> types = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(res.body(), StandardCharsets.UTF_8))) {
            String line;
            while (types.size() < want && (line = r.readLine()) != null) {
                if (line.startsWith("data: ")) {
                    types.add(Json.parse(line.substring(6)).get("type").asText());
                }
            }
        }
        return types;
    }

    private static String waitFor(ByteArrayOutputStream out, String text) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!out.toString().contains(text) && System.nanoTime() < end) {
            Thread.sleep(20);
        }
        assertTrue(out.toString().contains(text), out.toString());
        return out.toString();
    }

    @Test
    void viewServesASavedSession() throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path log = session(home, ws, "hello", "Hi there.");
        int lines = Files.readAllLines(log).size();
        CompletableFuture<SessionViewer> viewer = new CompletableFuture<>();
        CountDownLatch[] stop = new CountDownLatch[1];
        ViewCommand.started = (v, s) -> {
            stop[0] = s;
            viewer.complete(v);
        };
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Main m = new Main();
        m.out = new PrintStream(out, true);
        m.err = new PrintStream(new ByteArrayOutputStream(), true);
        CompletableFuture<Integer> code = CompletableFuture.supplyAsync(() -> Main.run(m, "view", "--home", home.toString(), "--cwd", ws.toString()));
        SessionViewer v = viewer.get(20, TimeUnit.SECONDS);
        List<String> types = events(http, v, lines);
        assertEquals("session.started", types.getFirst());
        assertTrue(types.contains("turn.started") && types.contains("model.completed") && types.contains("turn.completed"), types.toString());
        assertTrue(out.toString().contains("Open http://127.0.0.1:" + v.port() + "/?t="), out.toString());
        // Spec 052: the terminal shows each event too, as jharness log SESSION does, after the address.
        String printed = waitFor(out, "  turn.completed");
        assertTrue(printed.indexOf("Open http") < printed.indexOf("  session.started"), printed);
        assertTrue(printed.contains("  turn.started") && printed.contains("prompt=hello") && printed.contains("  model.completed"), printed);
        stop[0].countDown();
        assertEquals(0, code.get(20, TimeUnit.SECONDS));

        // --quiet prints only the address.
        CompletableFuture<SessionViewer> quiet = new CompletableFuture<>();
        ViewCommand.started = (qv, s) -> {
            stop[0] = s;
            quiet.complete(qv);
        };
        ByteArrayOutputStream qout = new ByteArrayOutputStream();
        Main q = new Main();
        q.out = new PrintStream(qout, true);
        q.err = new PrintStream(new ByteArrayOutputStream(), true);
        CompletableFuture<Integer> qcode = CompletableFuture.supplyAsync(() -> Main.run(q, "view", "--quiet", "--home", home.toString(), "--cwd", ws.toString()));
        assertEquals(lines, events(http, quiet.get(20, TimeUnit.SECONDS), lines).size(), "the browser still gets every event");
        stop[0].countDown();
        assertEquals(0, qcode.get(20, TimeUnit.SECONDS));
        assertEquals(2, qout.toString().lines().count(), qout.toString());

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Main bad = new Main();
        bad.out = new PrintStream(new ByteArrayOutputStream(), true);
        bad.err = new PrintStream(err, true);
        assertEquals(2, Main.run(bad, "view", "s-nope", "--home", home.toString(), "--cwd", ws.toString()));
        assertTrue(err.toString().contains("jharness view:"), err.toString());
    }

    @Test
    void theLiveFeedIsThisRunsEventsInOrder() throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home2"));
        Path ws = Files.createDirectories(tmp.resolve("ws2"));
        List<String> lines = new CopyOnWriteArrayList<>();
        try (Session s = Session.open(home, ws, Settings.defaults(), Session.Access.prompter(op -> Prompter.Answer.ONCE),
                (bus, turns) -> ReplayProvider.ofReplies(List.of("Streamed."), bus, turns))) {
            LiveFeed.follow(s, lines::add);
            s.turn("go", d -> {});
        }
        List<String> types = lines.stream().map(l -> Json.parse(l).get("type").asText()).toList();
        assertEquals("session.started", types.getFirst());
        assertTrue(types.contains("model.delta"), "chunks too, like stream-json: " + types);
        assertEquals(types.size(), lines.stream().map(l -> Json.parse(l).get("id").asText()).distinct().count(), "each event once");
        assertTrue(types.indexOf("turn.started") < types.indexOf("model.completed"));
    }
}
