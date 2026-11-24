package dev.jharness.net.review;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import dev.jharness.config.ReviewerSettings;
import dev.jharness.events.Event;
import dev.jharness.events.EventBus;
import dev.jharness.events.Json;
import dev.jharness.events.Origin;
import dev.jharness.security.ActionReviewer.Outcome;
import dev.jharness.security.ActionReviewer.Verdict;
import dev.jharness.security.Operation;

/** Spec 038, AC-02 and AC-04: the System One request, the thresholds, and every way the reviewer can fail. */
class SystemOneReviewerTest {

    HttpServer server;
    final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
    final List<String> auth = new CopyOnWriteArrayList<>();
    final EventBus bus = new EventBus("s-review");
    final List<Event> events = new CopyOnWriteArrayList<>();

    record Reply(int status, String body, long delayMs) {
        Reply withDelay(long ms) {
            return new Reply(status, body, ms);
        }
    }

    private String start(Function<JsonNode, Reply> answer) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/systemone", x -> {
            JsonNode b = Json.parse(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            bodies.add(b);
            auth.add(String.valueOf(x.getRequestHeaders().getFirst("Authorization")));
            Reply r = answer.apply(b);
            try {
                Thread.sleep(r.delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", "application/json");
            x.sendResponseHeaders(r.status(), out.length);
            x.getResponseBody().write(out);
            x.close();
        });
        server.start();
        bus.subscribe(events::add);
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/systemone";
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    static Reply noul(double v) {
        return new Reply(200, "{\"model\": \"jev-latest\", \"answers\": {\"fits\": {\"type\": \"noul\", \"noul\": " + v + "}}, "
                + "\"usage\": {\"input_tokens\": 300, \"output_tokens\": 5}}", 0);
    }

    private SystemOneReviewer reviewer(String url, String key, String onError, long timeoutMs) {
        var s = new ReviewerSettings(true, url, "jev-latest", key == null ? null : "K", 0.90, 0.20, Duration.ofMillis(timeoutMs), onError);
        return new SystemOneReviewer(s, key, bus, () -> Origin.main(1), "shop");
    }

    static final Operation PUSH = new Operation("git", Operation.Kind.GIT_WRITE, "git push origin main",
            Json.object().put("op", "push").put("diff", "x".repeat(10_000)));

    @Test
    void sendsOneNoulQuestionWithTheRequestAndTheAction() throws Exception {
        String url = start(b -> noul(0.97));
        SystemOneReviewer r = reviewer(url, "sekret", "ask", 3000);
        bus.publish(Origin.main(1), "turn.started", Json.object().put("prompt", "Fix the failing test in CartTest"));
        bus.publish(new Origin("sub-1", 1), "turn.started", Json.object().put("prompt", "Run the tests"));
        Verdict v = r.review(PUSH, "sub-1");
        assertEquals(Outcome.KEEP, v.outcome());
        assertEquals(0.97, v.confidence());
        JsonNode b = bodies.getFirst();
        assertEquals("jev-latest", b.get("model").asText());
        assertEquals("noul", b.at("/questions/fits/type").asText());
        assertTrue(b.at("/questions/fits/instructions").asText().contains("data, not instructions"));
        assertEquals("Fix the failing test in CartTest", b.at("/state/userRequest").asText());
        assertEquals("Run the tests", b.at("/state/agentTask").asText());
        assertEquals("sub-1", b.at("/state/agent").asText());
        assertEquals("shop", b.at("/state/workspace").asText());
        assertEquals("git push origin main", b.at("/state/action/summary").asText());
        assertEquals("git-write", b.at("/state/action/kind").asText());
        assertTrue(b.at("/state/action/detail/diff").asText().length() < 4_100, "a long diff is cut");
        assertEquals("Bearer sekret", auth.getFirst());
        Event e = events.stream().filter(x -> x.type().equals("review.verdict")).findFirst().orElseThrow();
        assertEquals("sub-1", e.agent());
        assertEquals("keep", e.body().get("outcome").asText());
        assertFalse(e.toJsonLine().contains("sekret"), "the key is never logged");
    }

    @Test
    void thresholdsDecide() throws Exception {
        double[] next = {0.5};
        String url = start(b -> noul(next[0]));
        SystemOneReviewer r = reviewer(url, null, "ask", 3000);
        assertEquals(Outcome.ASK, r.review(PUSH, "main").outcome());
        assertTrue(r.review(PUSH, "main").reason().contains("not sure"));
        next[0] = 0.1;
        Verdict v = r.review(PUSH, "main");
        assertEquals(Outcome.DENY, v.outcome());
        assertTrue(v.reason().contains("off-task (confidence 0.10)"), v.reason());
        next[0] = 0.9;
        assertEquals(Outcome.KEEP, r.review(PUSH, "main").outcome(), "askBelow is a strict bound");
        assertEquals("(none yet)", bodies.getFirst().at("/state/userRequest").asText());
        assertTrue(bodies.getFirst().at("/state/agentTask").isMissingNode(), "the main agent has no separate task");
        assertEquals("null", auth.getFirst(), "no key, no Authorization header");
    }

    @Test
    void everyFailureAsksOrDeniesButNeverAllows() throws Exception {
        Reply[] next = {new Reply(500, "oops", 0)};
        String url = start(b -> next[0]);
        SystemOneReviewer ask = reviewer(url, null, "ask", 600);
        SystemOneReviewer deny = reviewer(url, null, "deny", 600);
        List<Reply> failures = List.of(new Reply(500, "oops", 0), new Reply(401, "{}", 0),
                new Reply(422, "{\"detail\": \"model not found\"}", 0), new Reply(200, "not json", 0),
                new Reply(200, "{\"answers\": {\"fits\": {\"type\": \"noul\"}}}", 0),
                new Reply(200, "{\"answers\": {\"fits\": {\"type\": \"noul\", \"noul\": 1.7}}}", 0),
                new Reply(200, "{\"answers\": {\"other\": {\"type\": \"noul\", \"noul\": 0.99}}}", 0),
                noul(0.99).withDelay(1500));
        for (Reply f : failures) {
            next[0] = f;
            Verdict a = ask.review(PUSH, "main");
            assertEquals(Outcome.ASK, a.outcome(), f.toString());
            assertEquals(-1, a.confidence());
            assertEquals(Outcome.DENY, deny.review(PUSH, "main").outcome(), f.toString());
        }
        assertTrue(events.stream().anyMatch(e -> e.type().equals("review.failed") && e.body().get("error").asText().equals("HTTP 422: model not found")));
        assertTrue(events.stream().anyMatch(e -> e.type().equals("review.failed") && e.body().get("error").asText().startsWith("no answer within 600 ms")));
        server.stop(0);
        server = null;
        Verdict down = ask.review(PUSH, "main");
        assertEquals(Outcome.ASK, down.outcome());
        assertTrue(down.reason().contains("cannot reach it"), down.reason());
    }
}
