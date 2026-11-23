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
        return ModelProviders.create(p, bus, () -> Origin.main(1), env::get, waits());
    }

    /** Retry waits, recorded instead of slept (spec 020). */
    final List<java.time.Duration> waited = new ArrayList<>();

    private dev.jharness.llm.RetryWait waits() {
        return d -> {
            waited.add(d);
            return true;
        };
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
        assertEquals(List.of("model.request", "model.response", "model.delta", "model.delta", "model.completed"),
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
    void keyFromTheSecretsFileIsSentButNeverLogged(@org.junit.jupiter.api.io.TempDir java.nio.file.Path home) throws Exception {
        var f = dev.jharness.config.Secrets.fileFor(home);
        java.nio.file.Files.createDirectories(f.getParent());
        java.nio.file.Files.writeString(f, "TEST_KEY=sk-from-file-123\n");
        java.nio.file.Files.setPosixFilePermissions(f, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        var secrets = dev.jharness.config.Secrets.load(home, k -> null);
        Settings st = Settings.parse("{\"models\":{\"p\":{\"provider\":\"anthropic\",\"baseUrl\":\"" + stub.baseUrl()
                + "\",\"model\":\"m\",\"apiKeyEnv\":\"TEST_KEY\"}}}", "t");
        var log = dev.jharness.events.SessionLog.open(home, home, bus.session());
        bus.subscribe(log);
        stub.enqueue(Reply.sse(Streams.ANTHROPIC_OK));
        ModelProviders.create(st.models().resolve("p"), bus, () -> Origin.main(1), secrets).stream(req(), t -> {});
        log.close();
        assertEquals("sk-from-file-123", stub.requests().getFirst().header("x-api-key"));
        assertFalse(java.nio.file.Files.readString(log.path()).contains("sk-from-file-123"), "the key reached the session log");
    }

    @Test
    void anthropicErrors() throws Exception {
        var p = provider("anthropic", Map.of("TEST_KEY", "k"));
        stub.enqueue(Reply.json(401, Streams.ANTHROPIC_401));
        ModelException auth = assertThrows(ModelException.class, () -> p.stream(req(), t -> {}));
        assertTrue(auth.getMessage().contains("HTTP 401") && auth.getMessage().contains("authentication_error"), auth.getMessage());
        stub.enqueue(Reply.sse(Streams.ANTHROPIC_STREAM_ERROR)).enqueue(Reply.sse(Streams.ANTHROPIC_OK));
        assertFalse(p.stream(req(), t -> {}).text().isEmpty(), "an overloaded stream before any text is sent again");
        assertEquals(1, waited.size());
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
                () -> ModelProviders.create(s.models().resolve("p"), bus, () -> Origin.main(1), k -> null, waits()).stream(req(), t -> {}));
        assertTrue(down.getMessage().contains("cannot reach") && down.getMessage().contains("gave up after 5 attempts"), down.getMessage());
        assertEquals(4, waited.size());
    }

    private List<String> thinkingDeltas() {
        return events.stream().filter(e -> e.type().equals("model.thinking_delta")).map(e -> e.body().get("text").asText()).toList();
    }

    private com.fasterxml.jackson.databind.node.ObjectNode completed() {
        return events.stream().filter(e -> e.type().equals("model.completed")).findFirst().orElseThrow().body();
    }

    @Test
    void openAiReasoningFieldsAreThinking() throws Exception {
        stub.enqueue(Reply.sse(Streams.OPENAI_REASONING));
        List<String> seen = new ArrayList<>();
        ChatReply r = provider("openai-compatible", Map.of()).stream(req(), seen::add);
        assertEquals("Hi!", r.text(), "leading blank lines dropped");
        assertEquals("The user says hi. Greet back.", r.thinking());
        assertEquals(List.of("Hi!"), seen);
        assertEquals(List.of("The user says hi.", " Greet back."), thinkingDeltas());
        assertEquals("Hi!", completed().get("text").asText());
        assertEquals("The user says hi. Greet back.", completed().get("thinking").asText());
    }

    @Test
    void openAiInlineThinkTagsAreThinking() throws Exception {
        stub.enqueue(Reply.sse(Streams.OPENAI_THINK_TAGS));
        ChatReply r = provider("openai-compatible", Map.of()).stream(req(), t -> {});
        assertEquals("No code needed.", r.text());
        assertTrue(r.thinking().contains("api.writeFile('x', 'y')"), r.thinking());
        assertFalse(String.join("", deltas()).contains("think"), deltas().toString());
        assertTrue(dev.jharness.agent.JsBlocks.first(r.text()).isEmpty(), "the code inside <think> is not in the reply");
    }

    @Test
    void anthropicThinkingBlocks() throws Exception {
        stub.enqueue(Reply.sse(Streams.ANTHROPIC_THINKING));
        ChatReply r = provider("anthropic", Map.of("TEST_KEY", "k")).stream(req(), t -> {});
        assertEquals("Done.", r.text());
        assertEquals("Check the file.", r.thinking());
        assertEquals(List.of("Check the file."), thinkingDeltas());
        assertEquals("Check the file.", completed().get("thinking").asText());
    }

    @Test
    void completedCarriesTheReplyWithoutThinkingWhenThereIsNone() throws Exception {
        stub.enqueue(Reply.sse(Streams.OPENAI_OK));
        provider("openai-compatible", Map.of()).stream(req(), t -> {});
        assertEquals("```js\napi.readFile('a')\n```", completed().get("text").asText());
        assertFalse(completed().has("thinking"));
        assertFalse(completed().has("textCut"));
    }
}
