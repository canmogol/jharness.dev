package dev.jharness.llm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;

import dev.jharness.config.ModelPrice;
import dev.jharness.events.EventBus;
import dev.jharness.events.Json;
import dev.jharness.events.Origin;

/**
 * Plays back recorded replies for deterministic tests (US-6.2). A recording is JSONL, one line per
 * model call: {@code {"reply":"…"}}, optionally with {@code "thinking":"…"}, or {@code {"error":"…"}}. Emits the same events as a real
 * provider and makes no network connection.
 */
public final class ReplayProvider implements ModelProvider {

    private final List<JsonNode> lines;
    private final EventBus bus;
    private final Supplier<Origin> origin;
    private int next;
    private final List<ChatRequest> requests = new ArrayList<>();
    private volatile int contextWindow;
    private volatile ModelPrice price;

    public ReplayProvider(List<JsonNode> lines, EventBus bus, Supplier<Origin> origin) {
        this.lines = List.copyOf(lines);
        this.bus = bus;
        this.origin = origin;
    }

    /** Replies given as plain text, one per call. */
    public static ReplayProvider ofReplies(List<String> replies, EventBus bus, Supplier<Origin> origin) {
        List<JsonNode> l = new ArrayList<>();
        for (String r : replies) {
            l.add(Json.object().put("reply", r));
        }
        return new ReplayProvider(l, bus, origin);
    }

    public static ReplayProvider load(Path jsonl, EventBus bus, Supplier<Origin> origin) throws IOException {
        List<JsonNode> l = new ArrayList<>();
        for (String line : Files.readAllLines(jsonl, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                JsonNode n = Json.parse(line);
                if (!n.has("reply") && !n.has("error")) {
                    throw new IllegalArgumentException(jsonl + ": each line needs \"reply\" or \"error\"");
                }
                l.add(n);
            }
        }
        return new ReplayProvider(l, bus, origin);
    }

    @Override
    public String provider() {
        return "replay";
    }

    /** For tests and recordings (spec 039): the context window this replay claims; 0 means unknown. */
    public ReplayProvider contextWindow(int tokens) {
        contextWindow = tokens;
        return this;
    }

    /** For tests (spec 049): price the replies' token counts like a profile with this price. */
    public ReplayProvider price(ModelPrice p) {
        price = p;
        return this;
    }

    @Override
    public int contextWindow() {
        return contextWindow;
    }

    @Override
    public String model() {
        return "replay";
    }

    /** The requests seen so far, for tests. */
    public synchronized List<ChatRequest> requests() {
        return List.copyOf(requests);
    }

    public synchronized int remaining() {
        return lines.size() - next;
    }

    @Override
    public ChatReply stream(ChatRequest request, Consumer<String> onDelta) throws ModelException {
        return stream(request, onDelta, origin);
    }

    /** The same recording, consumed in the same order, with another agent's events under its name (spec 030). */
    @Override
    public ModelProvider forAgent(Supplier<Origin> as) {
        ReplayProvider shared = this;
        return new ModelProvider() {
            @Override
            public String provider() {
                return shared.provider();
            }

            @Override
            public String model() {
                return shared.model();
            }

            @Override
            public ChatReply stream(ChatRequest request, Consumer<String> onDelta) throws ModelException {
                return shared.stream(request, onDelta, as);
            }

            @Override
            public int contextWindow() {
                return shared.contextWindow();
            }

            @Override
            public void cancel() {
                shared.cancel();
            }
        };
    }

    private synchronized ChatReply stream(ChatRequest request, Consumer<String> onDelta, Supplier<Origin> origin) throws ModelException {
        requests.add(request);
        bus.publish(origin.get(), "model.request", Json.object().put("provider", "replay").put("model", "replay")
                .put("messages", request.messages().size()));
        if (next >= lines.size()) {
            String m = "replay recording has no more replies (" + lines.size() + " used)";
            bus.publish(origin.get(), "model.error", Json.object().put("provider", "replay").put("message", m));
            throw new ModelException(m);
        }
        JsonNode line = lines.get(next++);
        if (line.has("error")) {
            String m = line.get("error").asText();
            bus.publish(origin.get(), "model.error", Json.object().put("provider", "replay").put("message", m));
            throw new ModelException(m);
        }
        String text = line.get("reply").asText();
        String thinking = line.path("thinking").asText("");
        for (String t : lines(thinking)) {
            bus.publish(origin.get(), "model.thinking_delta", Json.object().put("text", t));
        }
        // Stream line by line, so consumers see more than one delta, as with a real model.
        int from = 0;
        while (from < text.length()) {
            int nl = text.indexOf('\n', from);
            int to = nl < 0 ? text.length() : nl + 1;
            String d = text.substring(from, to);
            bus.publish(origin.get(), "model.delta", Json.object().put("text", d));
            onDelta.accept(d);
            from = to;
        }
        // A recording may give the token counts the real model reported (spec 039).
        ChatReply reply = new ChatReply(text, thinking, "replay", line.path("inputTokens").asInt(0), line.path("outputTokens").asInt(0),
                line.path("cacheReadTokens").asInt(0), line.path("cacheWriteTokens").asInt(0));
        bus.publish(origin.get(), "model.completed", ModelEvents.completed("replay", "replay", reply, price));
        return reply;
    }

    private static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        int from = 0;
        while (from < text.length()) {
            int nl = text.indexOf('\n', from);
            int to = nl < 0 ? text.length() : nl + 1;
            out.add(text.substring(from, to));
            from = to;
        }
        return out;
    }
}
