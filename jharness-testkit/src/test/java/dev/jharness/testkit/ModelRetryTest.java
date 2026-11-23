package dev.jharness.testkit;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.jharness.config.Settings;
import dev.jharness.events.Event;
import dev.jharness.events.EventBus;
import dev.jharness.events.Origin;
import dev.jharness.llm.ChatMessage;
import dev.jharness.llm.ChatRequest;
import dev.jharness.llm.ModelException;
import dev.jharness.llm.ModelProvider;
import dev.jharness.llm.ModelProviders;
import dev.jharness.llm.RetryWait;
import dev.jharness.testkit.StubModelServer.Reply;

/** Spec 020: retries, rate limits and response timeouts for both providers, against the stub server. */
class ModelRetryTest {

    StubModelServer stub;
    final EventBus bus = new EventBus("s-retry");
    final List<Event> events = new CopyOnWriteArrayList<>();
    final List<Duration> waited = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        stub = new StubModelServer();
        bus.subscribe(events::add);
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    private final RetryWait record = d -> {
        waited.add(d);
        return true;
    };

    private ModelProvider anthropic(String extra, RetryWait w) throws Exception {
        Settings s = Settings.parse("{\"models\":{\"p\":{\"provider\":\"anthropic\",\"baseUrl\":\"" + stub.baseUrl()
                + "\",\"model\":\"m-1\",\"apiKeyEnv\":\"K\"" + extra + "}}}", "test");
        return ModelProviders.create(s.models().resolve("p"), bus, () -> Origin.main(1), k -> "key", w);
    }

    private ModelProvider anthropic() throws Exception {
        return anthropic("", record);
    }

    private ModelProvider openAi() throws Exception {
        Settings s = Settings.parse("{\"models\":{\"p\":{\"provider\":\"openai-compatible\",\"baseUrl\":\"" + stub.baseUrl()
                + "/v1\",\"model\":\"m-1\"}}}", "test");
        return ModelProviders.create(s.models().resolve("p"), bus, () -> Origin.main(1), k -> null, record);
    }

    private static ChatRequest req() {
        return new ChatRequest("You write JS.", List.of(ChatMessage.user("hi")), 0);
    }

    private List<Event> of(String type) {
        return events.stream().filter(e -> e.type().equals(type)).toList();
    }

    @Test
    void rateLimitWaitsForRetryAfterThenSucceeds() throws Exception {
        stub.enqueue(Reply.json(429, Streams.ANTHROPIC_RATE_LIMIT).with("retry-after", "7")).enqueue(Reply.sse(Streams.ANTHROPIC_OK));
        assertFalse(anthropic().stream(req(), t -> {}).text().isEmpty());
        assertEquals(List.of(Duration.ofSeconds(7)), waited);
        assertEquals(2, stub.requests().size());
        Event retry = of("model.retry").getFirst();
        assertEquals(2, retry.body().get("attempt").asInt());
        assertEquals(5, retry.body().get("attempts").asInt());
        assertEquals(7000, retry.body().get("waitMs").asLong());
        assertEquals("HTTP 429", retry.body().get("reason").asText());
        assertTrue(retry.body().get("message").asText().contains("rate_limit_error"), retry.body().toString());
        assertEquals(List.of(1, 2), of("model.request").stream().map(e -> e.body().get("attempt").asInt()).toList());
        assertTrue(of("model.error").isEmpty(), "a retry that succeeds is not an error");
    }

    @Test
    void retryAfterMsWinsAndServerErrorsBackOff() throws Exception {
        stub.enqueue(Reply.json(529, Streams.ANTHROPIC_OVERLOADED).with("retry-after-ms", "250"))
                .enqueue(Reply.json(503, "{}"))
                .enqueue(Reply.json(500, "{}"))
                .enqueue(Reply.sse(Streams.ANTHROPIC_OK));
        anthropic().stream(req(), t -> {});
        assertEquals(Duration.ofMillis(250), waited.get(0));
        // Back-off after the 2nd and 3rd failures: 2 s and 4 s, each cut to 50–100%.
        assertTrue(waited.get(1).toMillis() >= 1000 && waited.get(1).toMillis() <= 2000, waited.toString());
        assertTrue(waited.get(2).toMillis() >= 2000 && waited.get(2).toMillis() <= 4000, waited.toString());
    }

    @Test
    void clientErrorsAreNotRetried() throws Exception {
        for (int code : List.of(400, 401, 403, 404, 413, 422)) {
            stub.enqueue(Reply.json(code, "{\"error\":{\"message\":\"no\"}}"));
            ModelException e = assertThrows(ModelException.class, () -> openAi().stream(req(), t -> {}));
            assertTrue(e.getMessage().contains("HTTP " + code) && !e.getMessage().contains("gave up"), e.getMessage());
        }
        assertTrue(waited.isEmpty());
        assertTrue(of("model.retry").isEmpty());
    }

    @Test
    void theServersShouldRetryHeaderDecides() throws Exception {
        stub.enqueue(Reply.json(500, "{}").with("x-should-retry", "false"));
        assertThrows(ModelException.class, () -> anthropic().stream(req(), t -> {}));
        assertTrue(waited.isEmpty());
        stub.enqueue(Reply.json(400, "{}").with("x-should-retry", "true")).enqueue(Reply.sse(Streams.ANTHROPIC_OK));
        anthropic().stream(req(), t -> {});
        assertEquals(1, waited.size());
    }

    @Test
    void aRetryAfterLongerThanTheMaximumFailsAtOnce() throws Exception {
        stub.enqueue(Reply.json(429, Streams.ANTHROPIC_RATE_LIMIT).with("retry-after", "600"));
        ModelException e = assertThrows(ModelException.class, () -> anthropic(",\"retryMaxWait\":\"30s\"", record).stream(req(), t -> {}));
        assertTrue(e.getMessage().contains("asks to wait 600 s") && e.getMessage().contains("retryMaxWait (30 s)"), e.getMessage());
        assertTrue(waited.isEmpty());
        assertEquals(1, of("model.error").size());
    }

    @Test
    void givesUpAfterTheProfilesRetries() throws Exception {
        for (int i = 0; i < 3; i++) {
            stub.enqueue(Reply.json(503, "{}"));
        }
        ModelException e = assertThrows(ModelException.class, () -> anthropic(",\"retries\":2", record).stream(req(), t -> {}));
        assertTrue(e.getMessage().contains("HTTP 503") && e.getMessage().contains("gave up after 3 attempts"), e.getMessage());
        assertEquals(3, stub.requests().size());
        assertEquals(2, of("model.retry").size());
        assertEquals(1, of("model.error").size(), "one error, at the end");

        stub.enqueue(Reply.json(503, "{}"));
        ModelException once = assertThrows(ModelException.class, () -> anthropic(",\"retries\":0", record).stream(req(), t -> {}));
        assertFalse(once.getMessage().contains("gave up"), once.getMessage());
        assertEquals(4, stub.requests().size());
    }

    @Test
    void nothingTheUserSawIsSentAgain() throws Exception {
        stub.enqueue(Reply.sse(Streams.ANTHROPIC_OVERLOADED_AFTER_TEXT));
        assertTrue(assertThrows(ModelException.class, () -> anthropic().stream(req(), t -> {})).getMessage().contains("Overloaded"));
        stub.enqueue(Reply.sse(Streams.ANTHROPIC_CUT));
        assertThrows(ModelException.class, () -> anthropic().stream(req(), t -> {}));
        stub.enqueue(Reply.sse(Streams.OPENAI_CUT));
        assertThrows(ModelException.class, () -> openAi().stream(req(), t -> {}));
        assertEquals(3, stub.requests().size());
        assertTrue(waited.isEmpty());
    }

    @Test
    void escEndsTheWait() throws Exception {
        stub.enqueue(Reply.json(429, Streams.ANTHROPIC_RATE_LIMIT).with("retry-after", "30"));
        ModelProvider p = anthropic("", null);
        Thread.ofVirtual().start(() -> {
            while (of("model.retry").isEmpty()) {
                Thread.onSpinWait();
            }
            p.cancel();
        });
        long t0 = System.nanoTime();
        ModelException e = assertThrows(ModelException.class, () -> p.stream(req(), t -> {}));
        assertEquals("interrupted", e.getMessage());
        assertTrue(System.nanoTime() - t0 < Duration.ofSeconds(10).toNanos(), "the 30 s wait was not cut short");
        assertEquals(1, stub.requests().size());
    }
}
