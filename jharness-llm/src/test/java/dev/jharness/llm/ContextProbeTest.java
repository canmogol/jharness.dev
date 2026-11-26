package dev.jharness.llm;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import dev.jharness.config.ModelProfile;
import dev.jharness.events.EventBus;
import dev.jharness.events.Origin;

/** Spec 066: each kind of server reports the context it loaded, and the profile's contextWindow combines with it. */
class ContextProbeTest {

    HttpServer server;
    final List<String> asked = new ArrayList<>();
    final HttpClient http = HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null)).build();

    @BeforeEach
    void forget() {
        OpenAiCompatibleProvider.forgetReported();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** A fake server answering the given paths with JSON; every other path is a 404. */
    private String serve(Map<String, String> answers) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            synchronized (asked) {
                asked.add(ex.getRequestMethod() + " " + path);
            }
            String body = answers.get(path);
            byte[] b = (body == null ? "not found" : body).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(body == null ? 404 : 200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private static ModelProfile profile(String baseUrl, String model, Integer window) {
        return new ModelProfile("local", "openai-compatible", baseUrl, model, null, 8192, null, 0, Duration.ofSeconds(1),
                Duration.ofSeconds(5), window == null ? ModelProfile.DEFAULT_CONTEXT_WINDOW : window, false, null,
                ModelProfile.DEFAULT_SUMMARY_TOKENS, window != null);
    }

    private ContextProbe.Found probe(String base, String model) {
        return ContextProbe.probe(http, profile(base, model, null), null, ContextProbe.DEADLINE);
    }

    @Test
    void theServerRootDropsV1() {
        assertEquals("http://h:1", ContextProbe.root("http://h:1/v1"));
        assertEquals("http://h:1", ContextProbe.root("http://h:1/v1/"));
        assertEquals("http://h:1/api", ContextProbe.root("http://h:1/api"));
    }

    @Test
    void vllm() throws Exception {
        String base = serve(Map.of("/v1/models", "{\"data\":[{\"id\":\"other\",\"max_model_len\":4096},{\"id\":\"q\",\"max_model_len\":131072}]}"));
        assertEquals(new ContextProbe.Found("vLLM", 131072), probe(base, "q"));
    }

    @Test
    void llamaCpp() throws Exception {
        String base = serve(Map.of("/props", "{\"default_generation_settings\":{\"n_ctx\":65536},\"total_slots\":1}"));
        assertEquals(new ContextProbe.Found("llama.cpp", 65536), probe(base, "q"));
    }

    @Test
    void lmStudioUsesTheLoadedContext() throws Exception {
        String base = serve(Map.of("/api/v0/models", "{\"data\":[{\"id\":\"qwen/qwen3.6-27b\",\"state\":\"loaded\","
                + "\"max_context_length\":262144,\"loaded_context_length\":144000}]}"));
        assertEquals(new ContextProbe.Found("LM Studio", 144000), probe(base, "qwen/qwen3.6-27b"));
    }

    @Test
    void lmStudioV1UsesTheLoadedInstance() throws Exception {
        String base = serve(Map.of("/api/v1/models", "{\"models\":[{\"key\":\"other\",\"loaded_instances\":[]},"
                + "{\"key\":\"qwen/qwen3.6-27b\",\"max_context_length\":262144,"
                + "\"loaded_instances\":[{\"id\":\"qwen/qwen3.6-27b\",\"config\":{\"context_length\":144000}}]}]}"));
        assertEquals(new ContextProbe.Found("LM Studio", 144000), probe(base, "qwen/qwen3.6-27b"));
    }

    @Test
    void lmStudioNotLoadedReportsNothing() throws Exception {
        String base = serve(Map.of("/api/v1/models", "{\"models\":[{\"key\":\"q\",\"max_context_length\":262144,\"loaded_instances\":[]}]}"));
        assertNull(probe(base, "q"), "the model's maximum isn't what the server would load");
    }

    @Test
    void ollamaRunningModelThenItsParameters() throws Exception {
        String base = serve(Map.of("/api/ps", "{\"models\":[{\"name\":\"qwen2.5-coder:7b\",\"context_length\":16384}]}"));
        assertEquals(new ContextProbe.Found("Ollama", 16384), probe(base, "qwen2.5-coder:7b"));
        server.stop(0);
        base = serve(Map.of("/api/ps", "{\"models\":[]}", "/api/show", "{\"parameters\":\"stop \\\"<|im_end|>\\\"\\nnum_ctx 24576\"}"));
        assertEquals(new ContextProbe.Found("Ollama", 24576), probe(base, "qwen2.5-coder:7b"));
        assertTrue(asked.contains("POST /api/show"), asked.toString());
    }

    @Test
    void noAnswerIsNull() throws Exception {
        String base = serve(Map.of("/v1/models", "{\"data\":[{\"id\":\"q\"}]}", "/props", "not json"));
        assertNull(probe(base, "q"));
    }

    @Test
    void aServerThatHangsIsGivenUpOnAtTheDeadline() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ex.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        long t = System.nanoTime();
        var p = profile("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "q", null);
        assertNull(ContextProbe.probe(http, p, null, Duration.ofMillis(500)));
        assertTrue(System.nanoTime() - t < 3_000_000_000L, "one deadline for all the asks");
    }

    private OpenAiCompatibleProvider provider(ModelProfile p) {
        return new OpenAiCompatibleProvider(p, http, new EventBus("s-test"), () -> Origin.main(0), k -> null);
    }

    @Test
    void theWindowRules() throws Exception {
        String base = serve(Map.of("/props", "{\"default_generation_settings\":{\"n_ctx\":65536}}"));
        var reported = provider(profile(base, "q", null));
        String n = reported.discoverContext();
        assertEquals(65536, reported.contextWindow(), "no contextWindow: the server's");
        assertEquals("Context window: 65,536 tokens, as llama.cpp reports for q.", n);

        var bigger = provider(profile(base, "q", 144_000));
        String warn = bigger.discoverContext();
        assertEquals(65536, bigger.contextWindow(), "the server loaded less than the profile says: the smaller");
        assertTrue(warn.startsWith("llama.cpp loaded q with 65,536 tokens of context, less than profile local's contextWindow (144,000)"), warn);

        var smaller = provider(profile(base, "q", 32_000));
        assertNull(smaller.discoverContext());
        assertEquals(32_000, smaller.contextWindow(), "a profile under the server's context wins");
        assertEquals(1, asked.stream().filter(a -> a.endsWith("/props")).count(), "asked once per server and model");
    }

    @Test
    void nothingReportedMeansTheDefaultAndSaysSo() throws Exception {
        String base = serve(Map.of());
        var p = provider(profile(base, "q", null));
        String n = p.discoverContext();
        assertEquals(ModelProfile.DEFAULT_CONTEXT_WINDOW, p.contextWindow());
        assertTrue(n.contains("so JHarness assumes 32,768 tokens; set \"contextWindow\" in profile local"), n);
        var set = provider(profile(base, "q", 100_000));
        assertNull(set.discoverContext(), "a set window with nothing reported says nothing");
        assertEquals(100_000, set.contextWindow());
    }
}
