package dev.jharness.testkit;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.jharness.config.ModelProfile;
import dev.jharness.config.Settings;
import dev.jharness.events.Event;
import dev.jharness.events.EventBus;
import dev.jharness.events.Json;
import dev.jharness.events.Origin;
import dev.jharness.llm.ChatMessage;
import dev.jharness.llm.ChatReply;
import dev.jharness.llm.ChatRequest;
import dev.jharness.llm.ModelException;
import dev.jharness.llm.ModelProvider;
import dev.jharness.llm.ModelProviders;
import dev.jharness.testkit.StubModelServer.Reply;

class ProvidersTest {

    StubModelServer stub;
    final EventBus bus = new EventBus("s-llm");
    final List<Event> events = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        stub = new StubModelServer();
        bus.subscribe(events::add);
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    private ModelProvider provider(String provider, Map<String, String> env) throws Exception {
        String extra = provider.equals("anthropic") ? ",\"apiKeyEnv\":\"TEST_KEY\"" : "";
        Settings s = Settings.parse("{\"models\":{\"p\":{\"provider\":\"" + provider + "\",\"baseUrl\":\"" + stub.baseUrl()
                + (provider.equals("openai-compatible") ? "/v1" : "") + "\",\"model\":\"m-1\",\"maxTokens\":100" + extra + "}}}", "test");
        ModelProfile p = s.models().resolve("p");
        return ModelProviders.create(p, bus, () -> Origin.main(1), env::get);
    }

    private static ChatRequest req() {
        return new ChatRequest("You write JS.", List.of(ChatMessage.user("read a")), 0);
    }

    private List<String> deltas() {
        return events.stream().filter(e -> e.type().equals("model.delta")).map(e -> e.body().get("text").asText()).toList();
    }

    @Test
    void openAiStreamsDeltasAndReturnsTheReply() throws Exception {
        stub.enqueue(Reply.sse(Streams.OPENAI_OK));
        List<String> seen = new ArrayList<>();
        ChatReply r = provider("openai-compatible", Map.of()).stream(req(), seen::add);
        assertEquals("```js\napi.readFile('a')\n```", r.text());
        assertEquals("stop", r.stopReason());
        assertEquals(12, r.inputTokens());
        assertEquals(9, r.outputTokens());
        assertEquals(List.of("```js\n", "api.readFile('a')\n```"), seen);
        assertEquals(seen, deltas());
        var sent = stub.requests().getFirst();
        assertEquals("/v1/chat/completions", sent.path());
        var body = Json.parse(sent.body());
        assertEquals("m-1", body.get("model").asText());
        assertTrue(body.get("stream").asBoolean());
        assertEquals(100, body.get("max_tokens").asInt());
        assertEquals("system", body.get("messages").get(0).get("role").asText());
        assertEquals("read a", body.get("messages").get(1).get("content").asText());
        assertNull(sent.header("authorization"));
        assertEquals(List.of("model.request", "model.delta", "model.delta", "model.completed"),
                events.stream().map(Event::type).toList());
    }

    @Test
    void openAiErrors() throws Exception {
        stub.enqueue(Reply.json(404, Streams.OPENAI_ERROR));
        ModelException e = assertThrows(ModelException.class, () -> provider("openai-compatible", Map.of()).stream(req(), t -> {}));
        assertTrue(e.getMessage().contains("HTTP 404") && e.getMessage().contains("not found"), e.getMessage());
        assertEquals("model.error", events.getLast().type());

        stub.enqueue(Reply.sse(Streams.OPENAI_CUT));
        ModelException cut = assertThrows(ModelException.class, () -> provider("openai-compatible", Map.of()).stream(req(), t -> {}));
        assertTrue(cut.getMessage().contains("ended before"), cut.getMessage());
    }

    @Test
    void openAiSendsABearerKeyWhenConfigured() throws Exception {
        stub.enqueue(Reply.sse(Streams.OPENAI_OK));
        Settings s = Settings.parse("{\"models\":{\"p\":{\"provider\":\"openai-compatible\",\"baseUrl\":\"" + stub.baseUrl()
                + "\",\"model\":\"m\",\"apiKeyEnv\":\"OAI\"}}}", "t");
        ModelProviders.create(s.models().resolve("p"), bus, () -> Origin.main(1), Map.of("OAI", "sk-secret")::get).stream(req(), t -> {});
        assertEquals("Bearer sk-secret", stub.requests().getFirst().header("authorization"));
        assertTrue(events.stream().noneMatch(e -> e.toJsonLine().contains("sk-secret")), "the key reached an event");
    }

    @Test
    void anthropicStreamsDeltasAndReturnsTheReply() throws Exception {
        stub.enqueue(Reply.sse(Streams.ANTHROPIC_OK));
        ChatReply r = provider("anthropic", Map.of("TEST_KEY", "sk-ant-test")).stream(req(), t -> {});
        assertEquals("Hello, world", r.text());
        assertEquals("end_turn", r.stopReason());
        assertEquals(25, r.inputTokens());
        assertEquals(4, r.outputTokens());
        assertEquals(List.of("Hello", ", world"), deltas());
        var sent = stub.requests().getFirst();
        assertEquals("/v1/messages", sent.path());
        assertEquals("sk-ant-test", sent.header("x-api-key"));
        assertEquals("2023-06-01", sent.header("anthropic-version"));
        var body = Json.parse(sent.body());
        assertEquals("You write JS.", body.get("system").asText());
        assertEquals("user", body.get("messages").get(0).get("role").asText());
        assertTrue(events.stream().noneMatch(e -> e.toJsonLine().contains("sk-ant-test")), "the key reached an event");
    }

    @Test
    void anthropicErrors() throws Exception {
        var p = provider("anthropic", Map.of("TEST_KEY", "k"));
        stub.enqueue(Reply.json(401, Streams.ANTHROPIC_401));
        ModelException auth = assertThrows(ModelException.class, () -> p.stream(req(), t -> {}));
        assertTrue(auth.getMessage().contains("HTTP 401") && auth.getMessage().contains("authentication_error"), auth.getMessage());
        stub.enqueue(Reply.sse(Streams.ANTHROPIC_STREAM_ERROR));
        assertTrue(assertThrows(ModelException.class, () -> p.stream(req(), t -> {})).getMessage().contains("Overloaded"));
        stub.enqueue(Reply.sse(Streams.ANTHROPIC_CUT));
        assertTrue(assertThrows(ModelException.class, () -> p.stream(req(), t -> {})).getMessage().contains("message_stop"));
    }

    @Test
    void missingKeyAndUnreachableServer() throws Exception {
        ModelException noKey = assertThrows(ModelException.class, () -> provider("anthropic", Map.of()).stream(req(), t -> {}));
        assertTrue(noKey.getMessage().contains("TEST_KEY"), noKey.getMessage());
        assertTrue(stub.requests().isEmpty());
        Settings s = Settings.parse("{\"models\":{\"p\":{\"provider\":\"openai-compatible\",\"baseUrl\":\"http://127.0.0.1:1/v1\",\"model\":\"m\"}}}", "t");
        ModelException down = assertThrows(ModelException.class,
                () -> ModelProviders.create(s.models().resolve("p"), bus, () -> Origin.main(1), k -> null).stream(req(), t -> {}));
        assertTrue(down.getMessage().contains("cannot reach"), down.getMessage());
    }
}
