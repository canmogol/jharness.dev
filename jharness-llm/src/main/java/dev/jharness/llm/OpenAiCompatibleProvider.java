package dev.jharness.llm;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.jharness.config.ModelProfile;
import dev.jharness.events.EventBus;
import dev.jharness.events.Json;
import dev.jharness.events.Origin;

/** Streaming Chat Completions: Ollama, llama.cpp, vLLM and other OpenAI-compatible servers (D-013, D-035). */
public final class OpenAiCompatibleProvider extends HttpProvider {

    /** Chunk fields that carry thinking text: llama.cpp, LM Studio and vLLM use {@code reasoning_content}; Ollama and others {@code reasoning}. */
    private static final java.util.List<String> REASONING_FIELDS = java.util.List.of("reasoning_content", "reasoning");

    public OpenAiCompatibleProvider(ModelProfile profile, HttpClient http, EventBus bus, Supplier<Origin> origin,
            Function<String, String> env) {
        super(profile, http, bus, origin, env);
    }

    /** @param waiter waits before a retry (spec 020); null for the real wait */
    public OpenAiCompatibleProvider(ModelProfile profile, HttpClient http, EventBus bus, Supplier<Origin> origin,
            Function<String, String> env, RetryWait waiter) {
        super(profile, http, bus, origin, env, waiter);
    }

    /** Spec 066: what servers reported, by base URL and model, so subagents and /model don't ask again. */
    private static final java.util.Map<String, ContextProbe.Found> REPORTED = new java.util.concurrent.ConcurrentHashMap<>();
    private static final ContextProbe.Found NONE = new ContextProbe.Found("none", 0);

    private String key() {
        return profile.baseUrl() + "\n" + profile.model();
    }

    /**
     * Spec 066: the profile's contextWindow when set, else what the server reported, else the default. A server that
     * loaded the model with less than the profile says wins, since longer requests would fail.
     */
    @Override
    public int contextWindow() {
        ContextProbe.Found f = REPORTED.get(key());
        int reported = f == null ? 0 : f.tokens();
        if (profile.contextWindowSet()) {
            return reported > 0 ? Math.min(reported, profile.contextWindow()) : profile.contextWindow();
        }
        return reported > 0 ? reported : profile.contextWindow();
    }

    @Override
    public String discoverContext() {
        ContextProbe.Found f = REPORTED.get(key());
        if (f == null) {
            String k;
            try {
                k = apiKey(false);
            } catch (ModelException e) {
                k = null;
            }
            f = ContextProbe.probe(http, profile, k, ContextProbe.DEADLINE);
            REPORTED.put(key(), f == null ? NONE : f);
            if (f == null) {
                f = NONE;
            }
        }
        int window = contextWindow();
        String source = f.tokens() > 0 && window == f.tokens() ? "server" : profile.contextWindowSet() ? "profile" : "default";
        var ev = Json.object().put("profile", profile.name()).put("model", profile.model()).put("window", window).put("from", source);
        if (f.tokens() > 0) {
            ev.put("server", f.server()).put("reported", f.tokens());
        }
        if (profile.contextWindowSet()) {
            ev.put("set", profile.contextWindow());
        }
        bus.publish(origin.get(), "model.context_window", ev);
        if (f.tokens() > 0 && profile.contextWindowSet() && f.tokens() < profile.contextWindow()) {
            return String.format("%s loaded %s with %,d tokens of context, less than profile %s's contextWindow (%,d); using %,d.",
                    f.server(), profile.model(), f.tokens(), profile.name(), profile.contextWindow(), f.tokens());
        }
        if (f.tokens() > 0 && !profile.contextWindowSet()) {
            return String.format("Context window: %,d tokens, as %s reports for %s.", f.tokens(), f.server(), profile.model());
        }
        if (!profile.contextWindowSet()) {
            return String.format("The server didn't say how much context %s has, so JHarness assumes %,d tokens; set \"contextWindow\" in"
                    + " profile %s to the context the server loads.", profile.model(), window, profile.name());
        }
        return null;
    }

    /** Tests: forget what servers reported. */
    static void forgetReported() {
        REPORTED.clear();
    }

    @Override
    URI endpoint() {
        return URI.create(profile.baseUrl() + "/chat/completions");
    }

    @Override
    HttpRequest.Builder request(URI uri, String body) throws ModelException {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString(body));
        String key = apiKey(false);
        if (key != null) {
            b.header("authorization", "Bearer " + key);
        }
        return b;
    }

    @Override
    ObjectNode body(ChatRequest req) {
        ObjectNode o = Json.object();
        o.put("model", profile.model());
        o.put("stream", true);
        o.put("max_tokens", req.maxTokens() > 0 ? req.maxTokens() : profile.maxTokens());
        o.putObject("stream_options").put("include_usage", true);
        ArrayNode m = o.putArray("messages");
        if (req.system() != null && !req.system().isEmpty()) {
            m.addObject().put("role", "system").put("content", req.system());
        }
        for (ChatMessage c : req.messages()) {
            m.addObject().put("role", c.role()).put("content", c.content());
        }
        return o;
    }

    @Override
    ChatReply parse(InputStream in, Consumer<String> delta, Consumer<String> thinkingDelta) throws IOException, ModelException {
        StringBuilder text = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        Consumer<String> think = t -> {
            thinking.append(t);
            thinkingDelta.accept(t);
        };
        // A leading <think>…</think> in the content is thinking, not reply (D-046).
        ThinkSplitter split = new ThinkSplitter(t -> {
            text.append(t);
            delta.accept(t);
        }, think);
        String[] stop = {null};
        int[] usage = {0, 0, 0};
        boolean done = Sse.read(in, (event, data) -> {
            if (data.equals("[DONE]")) {
                return false;
            }
            JsonNode j;
            try {
                j = Json.parse(data);
            } catch (IllegalArgumentException e) {
                throw new ModelException("bad stream chunk from " + profile.baseUrl() + ": " + e.getMessage());
            }
            if (j.has("error")) {
                throw new ModelException("openai-compatible error: " + errorMessage(j));
            }
            JsonNode choice = j.path("choices").path(0);
            JsonNode d = choice.path("delta");
            for (String field : REASONING_FIELDS) {
                String r = d.path(field).isTextual() ? d.get(field).asText() : "";
                if (!r.isEmpty()) {
                    think.accept(r);
                }
            }
            split.feed(d.path("content").asText(""));
            if (choice.hasNonNull("finish_reason")) {
                stop[0] = choice.get("finish_reason").asText();
            }
            if (j.hasNonNull("usage")) {
                usage[0] = j.get("usage").path("prompt_tokens").asInt();
                usage[1] = j.get("usage").path("completion_tokens").asInt();
                // Already part of prompt_tokens (OpenAI, vLLM, LM Studio report it; spec 040).
                usage[2] = j.get("usage").path("prompt_tokens_details").path("cached_tokens").asInt();
            }
            return true;
        });
        split.finish();
        if (!done && stop[0] == null) {
            throw new ModelException("the stream from " + profile.baseUrl() + " ended before the reply was complete");
        }
        return new ChatReply(text.toString(), thinking.toString(), stop[0] == null ? "stop" : stop[0], usage[0], usage[1], usage[2], 0);
    }

    @Override
    String errorMessage(JsonNode body) {
        JsonNode e = body.path("error");
        if (e.isTextual()) {
            return e.asText();
        }
        String m = e.path("message").asText("");
        return m.isEmpty() ? Json.write(body) : m;
    }
}
