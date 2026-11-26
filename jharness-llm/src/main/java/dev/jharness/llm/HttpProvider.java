package dev.jharness.llm;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    final ModelProfile profile;
    final HttpClient http;
    final EventBus bus;
    final Supplier<Origin> origin;
    final Function<String, String> env;
    private volatile InputStream current;
    private volatile boolean cancelled;
    private final Object waitLock = new Object();
    private final RetryWait waiter;

    @Override
    public int contextWindow() {
        return profile.contextWindow();
    }

    @Override
    public int maxTokens() {
        return profile.maxTokens();
    }

    @Override
    public int summaryTokens() {
        return profile.summaryTokens();
    }

    @Override
    public void cancel() {
        cancelled = true;
        synchronized (waitLock) {
            waitLock.notifyAll();
        }
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
        this(profile, http, bus, origin, env, null);
    }

    /** @param waiter waits before a retry, or null for the real wait that cancel ends */
    HttpProvider(ModelProfile profile, HttpClient http, EventBus bus, Supplier<Origin> origin, Function<String, String> env,
            RetryWait waiter) {
        this.waiter = waiter != null ? waiter : this::defaultPause;
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
        int attempts = profile.retries() + 1;
        try {
            for (int n = 1; ; n++) {
                Failure f;
                try {
                    ChatReply reply = attempt(req, onDelta, n);
                    bus.publish(origin.get(), "model.completed", ModelEvents.completed(provider(), model(), reply, profile.price()));
                    return reply;
                } catch (Failure x) {
                    f = x;
                }
                if (cancelled) {
                    throw new ModelException("interrupted", f.getCause());
                }
                String gaveUp = n > 1 ? " (gave up after " + n + " attempts)" : "";
                if (!f.retryable || n >= attempts) {
                    throw new ModelException(f.getMessage() + gaveUp, f.getCause());
                }
                Duration wait;
                if (f.retryAfter != null) {
                    if (f.retryAfter.compareTo(profile.retryMaxWait()) > 0) {
                        throw new ModelException(f.getMessage() + "; the server asks to wait " + seconds(f.retryAfter)
                                + ", longer than retryMaxWait (" + seconds(profile.retryMaxWait()) + ")" + gaveUp, f.getCause());
                    }
                    wait = f.retryAfter;
                } else {
                    wait = backoff(n, profile.retryMaxWait());
                }
                bus.publish(origin.get(), "model.retry", Json.object().put("provider", provider()).put("model", model())
                        .put("profile", profile.name()).put("attempt", n + 1).put("attempts", attempts)
                        .put("waitMs", wait.toMillis()).put("reason", f.reason).put("message", f.getMessage()));
                if (!waiter.pause(wait)) {
                    throw new ModelException("interrupted");
                }
            }
        } catch (ModelException e) {
            error(e.getMessage());
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error("interrupted");
            throw new ModelException("interrupted", e);
        } finally {
            current = null;
        }
    }

    /** Response headers that go on {@code model.response}: none can hold a credential (spec 021 AC-02). */
    static boolean loggedHeader(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.equals("retry-after") || n.equals("retry-after-ms") || n.equals("x-should-retry") || n.equals("content-type")
                || n.startsWith("anthropic-ratelimit-") || n.startsWith("x-ratelimit-");
    }

    private ObjectNode response(HttpResponse<?> resp, int attempt, long ms) {
        ObjectNode o = Json.object().put("provider", provider()).put("model", model()).put("attempt", attempt)
                .put("status", resp.statusCode()).put("durationMs", ms);
        var h = resp.headers();
        h.firstValue("request-id").or(() -> h.firstValue("x-request-id")).ifPresent(id -> o.put("requestId", id));
        ObjectNode logged = Json.object();
        new java.util.TreeMap<>(h.map()).forEach((k, v) -> {
            if (loggedHeader(k) && !v.isEmpty()) {
                logged.put(k.toLowerCase(java.util.Locale.ROOT), String.join(", ", v));
            }
        });
        if (!logged.isEmpty()) {
            o.set("headers", logged);
        }
        return o;
    }

    /** One failed request that may be sent again; {@link #stream} decides. */
    static final class Failure extends Exception {
        final boolean retryable;
        final Duration retryAfter;
        final String reason;

        Failure(String message, Throwable cause, boolean retryable, Duration retryAfter, String reason) {
            super(message, cause);
            this.retryable = retryable;
            this.retryAfter = retryAfter;
            this.reason = reason;
        }
    }

    /** Sends the request once. Nothing that was streamed to the user is ever sent again. */
    private ChatReply attempt(ChatRequest req, Consumer<String> onDelta, int n) throws ModelException, Failure, InterruptedException {
        bus.publish(origin.get(), "model.request", Json.object().put("provider", provider()).put("model", model())
                .put("profile", profile.name()).put("messages", req.messages().size()).put("attempt", n));
        HttpRequest r = request(endpoint(), Json.write(body(req)))
                .timeout(profile.responseTimeout())
                .header("content-type", "application/json")
                .header("accept", "text/event-stream")
                .build();
        HttpResponse<InputStream> resp;
        long sent = System.nanoTime();
        try {
            resp = http.send(r, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            if (cancelled) {
                throw new ModelException("interrupted", e);
            }
            String m = "cannot reach " + provider() + " at " + profile.baseUrl() + ": " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + e.getMessage());
            throw new Failure(m, e, retryableIo(e), null, "connection: " + e.getClass().getSimpleName());
        }
        bus.publish(origin.get(), "model.response", response(resp, n, (System.nanoTime() - sent) / 1_000_000));
        if (resp.statusCode() != 200) {
            String text;
            try (InputStream in = resp.body()) {
                text = new String(in.readNBytes(4096), StandardCharsets.UTF_8);
            } catch (IOException e) {
                text = "";
            }
            String msg;
            try {
                msg = errorMessage(Json.parse(text));
            } catch (IllegalArgumentException e) {
                msg = text.length() > 300 ? text.substring(0, 300) + "…" : text;
            }
            int code = resp.statusCode();
            var h = resp.headers();
            boolean retry = h.firstValue("x-should-retry").map(v -> v.equalsIgnoreCase("true")).orElse(retryableStatus(code));
            throw new Failure(provider() + " returned HTTP " + code + ": " + msg, null, retry,
                    retryAfter(h.firstValue("retry-after-ms").orElse(null), h.firstValue("retry-after").orElse(null), Instant.now()),
                    "HTTP " + code);
        }
        boolean[] streamed = {false};
        try (InputStream in = resp.body()) {
            current = in;
            if (cancelled) {
                throw new ModelException("interrupted");
            }
            return parse(in, t -> {
                if (!t.isEmpty()) {
                    streamed[0] = true;
                    bus.publish(origin.get(), "model.delta", Json.object().put("text", t));
                    onDelta.accept(t);
                }
            }, t -> {
                if (!t.isEmpty()) {
                    streamed[0] = true;
                    bus.publish(origin.get(), "model.thinking_delta", Json.object().put("text", t));
                }
            });
        } catch (ModelException e) {
            if (e.retryable() && !streamed[0] && !cancelled) {
                throw new Failure(e.getMessage(), e, true, null, "stream error");
            }
            throw e;
        } catch (IOException e) {
            if (cancelled) {
                throw new ModelException("interrupted", e);
            }
            String m = "the stream from " + provider() + " broke: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + e.getMessage());
            throw new Failure(m, e, !streamed[0], null, "stream: " + e.getClass().getSimpleName());
        } finally {
            current = null;
        }
    }

    /** Statuses worth sending again, as the official SDKs do: 408, 409, 429 and every 5xx (529 is Anthropic's "overloaded"). */
    static boolean retryableStatus(int code) {
        return code == 408 || code == 409 || code == 429 || code >= 500;
    }

    /** The proxy's answer to CONNECT, as the JDK reports it. */
    private static final Pattern TUNNEL = Pattern.compile("(?:Tunnel failed, got: |Proxy returns \"HTTP/1\\.[01] )([0-9]{3})");

    /**
     * Connection failures are worth a retry, except a response timeout (the profile's patience ran out)
     * and a proxy that refused the tunnel (the network rules, which won't change on their own).
     */
    static boolean retryableIo(IOException e) {
        if (e instanceof HttpTimeoutException && !(e instanceof HttpConnectTimeoutException)) {
            return false;
        }
        Matcher m = TUNNEL.matcher(String.valueOf(e.getMessage()));
        if (m.find()) {
            return m.group(1).startsWith("5");
        }
        return true;
    }

    /** {@code retry-after-ms}, else {@code retry-after} in seconds or as an HTTP date; null when neither is usable. */
    static Duration retryAfter(String ms, String header, Instant now) {
        try {
            if (ms != null) {
                return Duration.ofMillis(Math.max(0, Math.round(Double.parseDouble(ms.strip()))));
            }
            if (header == null) {
                return null;
            }
            String v = header.strip();
            if (v.matches("[0-9]+(\\.[0-9]+)?")) {
                return Duration.ofMillis(Math.round(Double.parseDouble(v) * 1000));
            }
            Instant at = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration d = Duration.between(now, at);
            return d.isNegative() ? Duration.ZERO : d;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 1 s, 2 s, 4 s ... capped at {@code max}, each cut to a random 50–100% so clients don't retry in step. */
    static Duration backoff(int failedAttempt, Duration max) {
        long full = Math.min(max.toMillis(), 1000L << Math.min(failedAttempt - 1, 20));
        return Duration.ofMillis(full / 2 + ThreadLocalRandom.current().nextLong(full / 2 + 1));
    }

    private static String seconds(Duration d) {
        long s = (d.toMillis() + 999) / 1000;
        return s + " s";
    }

    /** Waits on {@link #waitLock}, so {@link #cancel} ends the wait at once. */
    private boolean defaultPause(Duration d) throws InterruptedException {
        long end = System.nanoTime() + d.toNanos();
        synchronized (waitLock) {
            while (!cancelled) {
                long left = end - System.nanoTime();
                if (left <= 0) {
                    return true;
                }
                waitLock.wait(Math.max(1, left / 1_000_000));
            }
        }
        return false;
    }

    private void error(String message) {
        bus.publish(origin.get(), "model.error", Json.object().put("provider", provider()).put("message", message));
    }
}
