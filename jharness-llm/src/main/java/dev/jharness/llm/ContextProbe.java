package dev.jharness.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;

import dev.jharness.config.ModelProfile;
import dev.jharness.events.Json;

/**
 * Spec 066: asks an OpenAI-compatible server how much context it loaded the model with. Each server says it its own way;
 * all are asked at once, with one deadline, and the first in the order below that answers wins. Requests go through the
 * same client as chat requests, so through the egress proxy.
 */
final class ContextProbe {

    /** What a server reported: its kind and the context in tokens. */
    record Found(String server, int tokens) {}

    static final Duration DEADLINE = Duration.ofSeconds(2);

    private ContextProbe() {}

    /** The server root: the base URL without a trailing {@code /v1}. */
    static String root(String baseUrl) {
        String b = baseUrl.replaceAll("/+$", "");
        return b.endsWith("/v1") ? b.substring(0, b.length() - 3) : b;
    }

    /** Asks every kind of server; null when none answered with a usable number before the deadline. */
    static Found probe(HttpClient http, ModelProfile p, String apiKey, Duration deadline) {
        String root = root(p.baseUrl());
        String model = p.model();
        List<CompletableFuture<Found>> asks = new ArrayList<>();
        // vLLM, and hosted servers such as OpenRouter: the model's entry in /v1/models.
        asks.add(get(http, p.baseUrl() + "/models", apiKey, deadline).thenApply(j -> {
            for (JsonNode m : j.path("data")) {
                if (model.equals(m.path("id").asText())) {
                    int n = firstInt(m, "max_model_len", "context_length");
                    return n > 0 ? new Found(m.has("max_model_len") ? "vLLM" : "the server's model list", n) : null;
                }
            }
            return null;
        }));
        // llama.cpp: /props, the context of one slot.
        asks.add(get(http, root + "/props", apiKey, deadline).thenApply(j -> {
            int n = j.path("default_generation_settings").path("n_ctx").asInt(0);
            if (n <= 0) {
                n = j.path("n_ctx").asInt(0);
            }
            return n > 0 ? new Found("llama.cpp", n) : null;
        }));
        // LM Studio 0.4 and later: /api/v1/models, the context of a loaded instance.
        asks.add(get(http, root + "/api/v1/models", apiKey, deadline).thenApply(j -> {
            for (JsonNode m : j.path("models")) {
                if (model.equals(m.path("key").asText())) {
                    for (JsonNode i : m.path("loaded_instances")) {
                        int n = i.path("config").path("context_length").asInt(0);
                        if (n > 0) {
                            return new Found("LM Studio", n);
                        }
                    }
                    return null;
                }
            }
            return null;
        }));
        // Older LM Studio: /api/v0/models, the context it loaded the model with.
        asks.add(get(http, root + "/api/v0/models", apiKey, deadline).thenApply(j -> {
            for (JsonNode m : j.path("data")) {
                if (model.equals(m.path("id").asText())) {
                    int n = m.path("loaded_context_length").asInt(0);
                    return n > 0 ? new Found("LM Studio", n) : null;
                }
            }
            return null;
        }));
        // Ollama: /api/ps for a loaded model's context, else a num_ctx parameter from /api/show.
        asks.add(get(http, root + "/api/ps", apiKey, deadline).thenApply(j -> {
            for (JsonNode m : j.path("models")) {
                if (model.equals(m.path("name").asText()) || model.equals(m.path("model").asText())) {
                    int n = m.path("context_length").asInt(0);
                    return n > 0 ? new Found("Ollama", n) : null;
                }
            }
            return null;
        }));
        asks.add(post(http, root + "/api/show", apiKey, Json.write(Json.object().put("model", model)), deadline).thenApply(j -> {
            String params = j.path("parameters").asText("");
            for (String line : params.split("\n")) {
                String[] w = line.strip().split("\\s+");
                if (w.length == 2 && w[0].equals("num_ctx")) {
                    try {
                        return new Found("Ollama", Integer.parseInt(w[1]));
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
            }
            return null;
        }));
        long end = System.nanoTime() + deadline.toNanos();
        Found best = null;
        for (CompletableFuture<Found> a : asks) {
            try {
                long left = Math.max(1, end - System.nanoTime());
                Found f = a.get(left, TimeUnit.NANOSECONDS);
                if (f != null && f.tokens() >= ModelProfile.MIN_CONTEXT_WINDOW && f.tokens() <= ModelProfile.MAX_CONTEXT_WINDOW) {
                    best = f;
                    break;
                }
            } catch (Exception e) {
                // No answer from this kind of server: try the next.
            }
        }
        asks.forEach(a -> a.cancel(true));
        return best;
    }

    private static int firstInt(JsonNode n, String... fields) {
        for (String f : fields) {
            if (n.path(f).canConvertToInt() && n.path(f).asInt() > 0) {
                return n.path(f).asInt();
            }
        }
        return 0;
    }

    private static CompletableFuture<JsonNode> get(HttpClient http, String url, String key, Duration timeout) {
        return send(http, builder(url, key, timeout).GET().build());
    }

    private static CompletableFuture<JsonNode> post(HttpClient http, String url, String key, String body, Duration timeout) {
        return send(http, builder(url, key, timeout).header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private static HttpRequest.Builder builder(String url, String key, Duration timeout) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).header("accept", "application/json");
        if (key != null) {
            b.header("authorization", "Bearer " + key);
        }
        return b;
    }

    private static CompletableFuture<JsonNode> send(HttpClient http, HttpRequest r) {
        try {
            return http.sendAsync(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenApply(resp -> {
                if (resp.statusCode() != 200 || resp.body().length() > 4_000_000) {
                    return Json.object();
                }
                try {
                    return Json.parse(resp.body());
                } catch (IllegalArgumentException e) {
                    return Json.object();
                }
            });
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(Json.object());
        }
    }
}
