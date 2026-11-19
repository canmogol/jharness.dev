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

/** Native streaming Anthropic Messages client for Claude (D-013). */
public final class AnthropicProvider extends HttpProvider {

    static final String VERSION = "2023-06-01";

    public AnthropicProvider(ModelProfile profile, HttpClient http, EventBus bus, Supplier<Origin> origin,
            Function<String, String> env) {
        super(profile, http, bus, origin, env);
    }

    @Override
    URI endpoint() {
        return URI.create(profile.baseUrl() + "/v1/messages");
    }

    @Override
    HttpRequest.Builder request(URI uri, String body) throws ModelException {
        return HttpRequest.newBuilder(uri)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("x-api-key", apiKey(true))
                .header("anthropic-version", VERSION);
    }

    @Override
    ObjectNode body(ChatRequest req) {
        ObjectNode o = Json.object();
        o.put("model", profile.model());
        o.put("max_tokens", req.maxTokens() > 0 ? req.maxTokens() : profile.maxTokens());
        o.put("stream", true);
        if (req.system() != null && !req.system().isEmpty()) {
            o.put("system", req.system());
        }
        ArrayNode m = o.putArray("messages");
        for (ChatMessage c : req.messages()) {
            m.addObject().put("role", c.role()).put("content", c.content());
        }
        return o;
    }

    @Override
    ChatReply parse(InputStream in, Consumer<String> delta, Consumer<String> thinkingDelta) throws IOException, ModelException {
        StringBuilder text = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        String[] stop = {null};
        int[] usage = {0, 0};
        boolean[] stopped = {false};
        Sse.read(in, (event, data) -> {
            JsonNode j;
            try {
                j = Json.parse(data);
            } catch (IllegalArgumentException e) {
                throw new ModelException("bad stream event from Anthropic: " + e.getMessage());
            }
            String type = j.path("type").asText(event == null ? "" : event);
            switch (type) {
                case "message_start" -> usage[0] = j.path("message").path("usage").path("input_tokens").asInt();
                case "content_block_delta" -> {
                    JsonNode d = j.path("delta");
                    if ("text_delta".equals(d.path("type").asText())) {
                        String t = d.path("text").asText("");
                        text.append(t);
                        delta.accept(t);
                    } else if ("thinking_delta".equals(d.path("type").asText())) {
                        String t = d.path("thinking").asText("");
                        thinking.append(t);
                        thinkingDelta.accept(t);
                    }
                }
                case "message_delta" -> {
                    if (j.path("delta").hasNonNull("stop_reason")) {
                        stop[0] = j.path("delta").get("stop_reason").asText();
                    }
                    if (j.path("usage").has("output_tokens")) {
                        usage[1] = j.path("usage").get("output_tokens").asInt();
                    }
                }
                case "message_stop" -> {
                    stopped[0] = true;
                    return false;
                }
                case "error" -> throw new ModelException("Anthropic error: " + errorMessage(j));
                default -> {
                    // ping, content_block_start, content_block_stop
                }
            }
            return true;
        });
        if (!stopped[0]) {
            throw new ModelException("the stream from Anthropic ended before message_stop");
        }
        return new ChatReply(text.toString(), thinking.toString(), stop[0] == null ? "end_turn" : stop[0], usage[0], usage[1]);
    }

    @Override
    String errorMessage(JsonNode body) {
        JsonNode e = body.path("error");
        String type = e.path("type").asText("");
        String m = e.path("message").asText("");
        if (m.isEmpty()) {
            return Json.write(body);
        }
        return type.isEmpty() ? m : type + ": " + m;
    }
}
