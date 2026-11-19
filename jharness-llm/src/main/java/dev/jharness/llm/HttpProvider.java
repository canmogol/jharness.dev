package dev.jharness.llm;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.jharness.config.ModelProfile;
import dev.jharness.events.EventBus;
import dev.jharness.events.Json;
import dev.jharness.events.Origin;

/** Shared HTTP, event and error handling for both providers. API keys never reach events. */
abstract class HttpProvider implements ModelProvider {

    static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(120);

    final ModelProfile profile;
    final HttpClient http;
    final EventBus bus;
    final Supplier<Origin> origin;
    final Function<String, String> env;
    private volatile InputStream current;
    private volatile boolean cancelled;

    @Override
    public void cancel() {
        cancelled = true;
        InputStream in = current;
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
                // Closing ends the blocked read.
            }
        }
    }

    HttpProvider(ModelProfile profile, HttpClient http, EventBus bus, Supplier<Origin> origin, Function<String, String> env) {
        this.profile = profile;
        this.http = http;
        this.bus = bus;
        this.origin = origin;
        this.env = env;
    }

    @Override
    public String provider() {
        return profile.provider();
    }

    @Override
    public String model() {
        return profile.model();
    }

    String apiKey(boolean required) throws ModelException {
        if (profile.apiKeyEnv() == null) {
            if (required) {
                throw new ModelException("profile " + profile.name() + " needs \"apiKeyEnv\"");
            }
            return null;
        }
        String k = env.apply(profile.apiKeyEnv());
        if (k == null || k.isBlank()) {
            if (required) {
                throw new ModelException("set the environment variable " + profile.apiKeyEnv() + " for model profile " + profile.name());
            }
            return null;
        }
        return k;
    }

    /** Builds the request; implementations add headers. */
    abstract HttpRequest.Builder request(URI uri, String body) throws ModelException;

    abstract URI endpoint();

    abstract ObjectNode body(ChatRequest req);

    /** Parses the event stream, calling {@code delta} per reply chunk and {@code thinking} per thinking chunk; returns the reply. */
    abstract ChatReply parse(InputStream in, Consumer<String> delta, Consumer<String> thinking) throws IOException, ModelException;

    /** The message from an error body, or a cut-down body. */
    abstract String errorMessage(JsonNode body);

    @Override
    public final ChatReply stream(ChatRequest req, Consumer<String> onDelta) throws ModelException {
        cancelled = false;
        bus.publish(origin.get(), "model.request", Json.object().put("provider", provider()).put("model", model())
                .put("profile", profile.name()).put("messages", req.messages().size()));
        try {
            HttpRequest r = request(endpoint(), Json.write(body(req)))
                    .timeout(RESPONSE_TIMEOUT)
                    .header("content-type", "application/json")
                    .header("accept", "text/event-stream")
                    .build();
            HttpResponse<InputStream> resp = http.send(r, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                String text;
                try (InputStream in = resp.body()) {
                    text = new String(in.readNBytes(4096), StandardCharsets.UTF_8);
                }
                String msg;
                try {
                    msg = errorMessage(Json.parse(text));
                } catch (IllegalArgumentException e) {
                    msg = text.length() > 300 ? text.substring(0, 300) + "…" : text;
                }
                throw new ModelException(provider() + " returned HTTP " + resp.statusCode() + ": " + msg);
            }
            ChatReply reply;
            try (InputStream in = resp.body()) {
                current = in;
                if (cancelled) {
                    throw new ModelException("interrupted");
                }
                reply = parse(in, t -> {
                    if (!t.isEmpty()) {
                        bus.publish(origin.get(), "model.delta", Json.object().put("text", t));
                        onDelta.accept(t);
                    }
                }, t -> {
                    if (!t.isEmpty()) {
                        bus.publish(origin.get(), "model.thinking_delta", Json.object().put("text", t));
                    }
                });
            }
            bus.publish(origin.get(), "model.completed", ModelEvents.completed(provider(), model(), reply));
            return reply;
        } catch (ModelException e) {
            error(e.getMessage());
            throw e;
        } catch (IOException e) {
            if (cancelled) {
                error("interrupted");
                throw new ModelException("interrupted", e);
            }
            String m = "cannot reach " + provider() + " at " + profile.baseUrl() + ": " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + e.getMessage());
            error(m);
            throw new ModelException(m, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error("interrupted");
            throw new ModelException("interrupted", e);
        } finally {
            current = null;
        }
    }

    private void error(String message) {
        bus.publish(origin.get(), "model.error", Json.object().put("provider", provider()).put("message", message));
    }
}
