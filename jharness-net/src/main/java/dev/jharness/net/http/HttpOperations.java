package dev.jharness.net.http;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.jharness.config.HttpSettings;
import dev.jharness.config.Timeouts;
import dev.jharness.events.EventBus;
import dev.jharness.events.Json;
import dev.jharness.events.Origin;
import dev.jharness.net.EgressProxy;
import dev.jharness.security.Operation;

/**
 * {@code api.http} and {@code api.fetch} (spec 016, D-054). Requests are checked here, sent with the one
 * client from {@link HttpClients} (so through the egress proxy and its rules), redirects are followed by
 * hand, responses are read up to a cap and returned as text, and every call emits {@code http.request}
 * and {@code http.response}. The events carry headers and bodies only when {@code http.logHeaders} or
 * {@code http.logBodies} is on, for debugging; secret headers are then redacted unless
 * {@code http.redactSecrets} is off.
 */
public final class HttpOperations {

    public static final String USER_AGENT = "JHarness/0.1.0-SNAPSHOT";
    static final int MAX_URL = 8192;
    static final int MAX_BODY_BYTES = 1 << 20;
    static final int MAX_HEADERS = 50;
    static final int MAX_REDIRECTS = 5;

    static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE");
    static final Set<String> READS = Set.of("GET", "HEAD");
    /** Headers the agent may not set: hop-by-hop, framing and proxy ones (AC-02); the JDK also refuses some. */
    static final Set<String> FORBIDDEN = Set.of("host", "content-length", "connection", "transfer-encoding", "upgrade",
            "keep-alive", "te", "trailer", "expect");
    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,100}");
    private static final Set<String> HTTP_FIELDS = Set.of("method", "url", "headers", "body", "timeoutMs");
    private static final Set<String> FETCH_FIELDS = Set.of("url", "timeoutMs");
    static final String FETCH_ACCEPT = "text/html, text/plain;q=0.9, */*;q=0.1";
    static final String REDACTED = "[redacted]";
    private static final Set<String> SECRET_HEADERS = Set.of("authorization", "proxy-authorization", "cookie", "set-cookie");
    private static final List<String> SECRET_WORDS = List.of("token", "key", "secret", "auth", "password", "session");

    /** A checked request. */
    public record Request(String method, URI uri, Map<String, String> headers, String body, Duration timeout) {

        /** The permission kind (AC-04). */
        public Operation.Kind kind() {
            return READS.contains(method) ? Operation.Kind.HTTP : Operation.Kind.HTTP_WRITE;
        }

        /** One line for the approval prompt, e.g. {@code POST https://api.example.com/items}. */
        public String summary() {
            return method + " " + uri;
        }

        /** Detail for the permission engine; {@code op} is the "always" rule key {@code <METHOD> <host>}. */
        public ObjectNode detail() {
            return Json.object().put("method", method).put("url", uri.toString()).put("host", uri.getHost())
                    .put("op", method + " " + uri.getHost().toLowerCase(Locale.ROOT));
        }
    }

    /** A response read up to the cap, before it's shaped for the model. */
    record Response(int status, URI url, HttpHeaders headers, String contentType, String text, long bytes,
            boolean truncated, boolean binary, int redirects) {}

    private final EventBus bus;
    private final Supplier<Origin> origin;
    private final HttpSettings settings;
    private final Timeouts timeouts;
    private final Supplier<HttpClient> client;

    public HttpOperations(EventBus bus, Supplier<Origin> origin, HttpSettings settings, Timeouts timeouts) {
        this(bus, origin, settings, timeouts, () -> {
            if (!HttpClients.proxied()) {
                throw new HttpException("no egress proxy in this session, so no request can be sent");
            }
            return HttpClients.create();
        });
    }

    HttpOperations(EventBus bus, Supplier<Origin> origin, HttpSettings settings, Timeouts timeouts, Supplier<HttpClient> client) {
        this.bus = bus;
        this.origin = origin;
        this.settings = settings;
        this.timeouts = timeouts;
        this.client = client;
    }

    // --- Checking (AC-01, AC-02) ---------------------------------------------------------------

    /** Checks an {@code api.http} request; throws {@link IllegalArgumentException} naming the rule. */
    public Request check(ObjectNode req) {
        fields(req, HTTP_FIELDS, "http");
        String method = "GET";
        JsonNode m = req.get("method");
        if (m != null && !m.isNull()) {
            if (!m.isTextual() || !METHODS.contains(m.asText().toUpperCase(Locale.ROOT))) {
                throw new IllegalArgumentException("\"method\" must be one of DELETE, GET, HEAD, PATCH, POST, PUT");
            }
            method = m.asText().toUpperCase(Locale.ROOT);
        }
        URI uri = url(req);
        Map<String, String> headers = headers(req.get("headers"));
        String body = null;
        JsonNode b = req.get("body");
        if (b != null && !b.isNull()) {
            if (!b.isTextual()) {
                throw new IllegalArgumentException("\"body\" must be a string (use JSON.stringify for JSON)");
            }
            if (READS.contains(method)) {
                throw new IllegalArgumentException(method + " requests take no \"body\"");
            }
            if (b.asText().getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
                throw new IllegalArgumentException("\"body\" is over " + MAX_BODY_BYTES + " bytes");
            }
            body = b.asText();
        }
        return new Request(method, uri, headers, body, timeout(req));
    }

    /** Checks an {@code api.fetch} request: a GET with the page {@code Accept} header. */
    public Request checkFetch(ObjectNode req) {
        fields(req, FETCH_FIELDS, "fetch");
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", FETCH_ACCEPT);
        return new Request("GET", url(req), h, null, timeout(req));
    }

    private static void fields(ObjectNode req, Set<String> allowed, String api) {
        for (Iterator<String> it = req.fieldNames(); it.hasNext(); ) {
            String f = it.next();
            if (!allowed.contains(f)) {
                throw new IllegalArgumentException(api + " does not take \"" + f + "\" (takes: " + String.join(", ", new java.util.TreeSet<>(allowed)) + ")");
            }
        }
    }

    static URI url(ObjectNode req) {
        JsonNode u = req.get("url");
        if (u == null || !u.isTextual() || u.asText().isBlank()) {
            throw new IllegalArgumentException("\"url\" is required: an absolute http or https URL");
        }
        String s = u.asText().strip();
        if (s.length() > MAX_URL) {
            throw new IllegalArgumentException("\"url\" is over " + MAX_URL + " characters");
        }
        URI uri;
        try {
            uri = new URI(s);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("\"url\" is not a valid URL: " + e.getReason());
        }
        return absolute(uri);
    }

    /** An http or https URL with a host and no user info; the fragment is dropped. */
    static URI absolute(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("\"url\" must start with http:// or https://");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("\"url\" may not contain a user name or password");
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new IllegalArgumentException("\"url\" has no host");
        }
        try {
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            return new URI(scheme + "://" + uri.getRawAuthority() + path + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : ""));
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("\"url\" is not a valid URL: " + e.getReason());
        }
    }

    static Map<String, String> headers(JsonNode h) {
        Map<String, String> out = new LinkedHashMap<>();
        if (h == null || h.isNull()) {
            return out;
        }
        if (!h.isObject()) {
            throw new IllegalArgumentException("\"headers\" must be an object of name: value strings");
        }
        if (h.size() > MAX_HEADERS) {
            throw new IllegalArgumentException("at most " + MAX_HEADERS + " headers");
        }
        Set<String> seen = new java.util.HashSet<>();
        for (Map.Entry<String, JsonNode> e : h.properties()) {
            String name = e.getKey();
            String lower = name.toLowerCase(Locale.ROOT);
            if (!TOKEN.matcher(name).matches()) {
                throw new IllegalArgumentException("header name \"" + name + "\" is not a valid HTTP token");
            }
            if (FORBIDDEN.contains(lower) || lower.startsWith("proxy-")) {
                throw new IllegalArgumentException("header \"" + name + "\" is set by JHarness and the proxy, not by agent code");
            }
            if (!seen.add(lower)) {
                throw new IllegalArgumentException("header \"" + name + "\" is given twice");
            }
            if (!e.getValue().isTextual()) {
                throw new IllegalArgumentException("header \"" + name + "\" must have a string value");
            }
            String v = e.getValue().asText();
            if (v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("header \"" + name + "\" contains a line break or NUL");
            }
            out.put(name, v);
        }
        return out;
    }

    private Duration timeout(ObjectNode req) {
        JsonNode t = req.get("timeoutMs");
        if (t == null || t.isNull()) {
            return timeouts.http();
        }
        long max = timeouts.httpMax().toMillis();
        if (!t.canConvertToLong() || !t.isIntegralNumber() || t.asLong() < 1 || t.asLong() > max) {
            throw new IllegalArgumentException("\"timeoutMs\" must be a whole number from 1 to " + max);
        }
        return Duration.ofMillis(t.asLong());
    }

    // --- Sending (AC-03, AC-05, AC-07, AC-08) --------------------------------------------------

    /** Sends an {@code api.http} request and returns its result object. */
    public ObjectNode http(Request r) {
        Response resp = send(r);
        String body = resp.binary() ? "" : resp.text();
        boolean cut = resp.truncated();
        if (body.length() > settings.maxChars()) {
            body = body.substring(0, settings.maxChars());
            cut = true;
        }
        ObjectNode o = Json.object().put("status", resp.status()).put("url", resp.url().toString());
        ObjectNode h = o.putObject("headers");
        headerMap(resp.headers()).forEach(h::put);
        return o.put("contentType", resp.contentType()).put("body", body).put("bytes", resp.bytes()).put("truncated", cut)
                .put("binary", resp.binary());
    }

    /** Sends an {@code api.fetch} request and returns the page as text (AC-06). */
    public ObjectNode fetch(Request r) {
        Response resp = send(r);
        if (resp.binary()) {
            throw new HttpException("not a text page (" + resp.contentType() + ")");
        }
        String title = "";
        String text = resp.text();
        String type = resp.contentType().toLowerCase(Locale.ROOT);
        if (type.startsWith("text/html") || type.startsWith("application/xhtml+xml")) {
            PageText.Result page = PageText.of(text, resp.url());
            title = page.title();
            text = page.text();
        }
        boolean cut = resp.truncated();
        if (text.length() > settings.maxChars()) {
            text = text.substring(0, settings.maxChars());
            cut = true;
        }
        return Json.object().put("status", resp.status()).put("url", resp.url().toString()).put("title", title)
                .put("text", text).put("truncated", cut);
    }

    Response send(Request r) {
        long t0 = System.nanoTime();
        long deadline = t0 + r.timeout().toNanos();
        ObjectNode ev = Json.object().put("method", r.method()).put("url", r.uri().toString());
        if (settings.logHeaders()) {
            Map<String, String> sent = new TreeMap<>();
            r.headers().forEach((k, v) -> sent.put(k.toLowerCase(Locale.ROOT), v));
            sent.putIfAbsent("user-agent", USER_AGENT);
            logHeaders(ev, sent);
        }
        if (settings.logBodies() && r.body() != null) {
            ev.put("body", r.body());
        }
        bus.publish(origin.get(), "http.request", ev);
        URI uri = r.uri();
        int redirects = 0;
        try {
            while (true) {
                Response resp = once(r, uri, deadline, redirects);
                String loc = resp.headers().firstValue("location").orElse(null);
                boolean redirect = switch (resp.status()) {
                    case 301, 302, 303, 307, 308 -> true;
                    default -> false;
                };
                if (!redirect || loc == null || !READS.contains(r.method())) {
                    completed(r, resp, t0, null);
                    return resp;
                }
                if (redirects == MAX_REDIRECTS) {
                    throw new HttpException("more than " + MAX_REDIRECTS + " redirects (last to " + loc + ")");
                }
                try {
                    uri = absolute(uri.resolve(new URI(loc.strip())));
                } catch (URISyntaxException | IllegalArgumentException e) {
                    throw new HttpException("bad redirect to " + loc);
                }
                redirects++;
            }
        } catch (RuntimeException e) {
            completed(r, null, t0, e.getMessage());
            throw e;
        }
    }

    private Response once(Request r, URI uri, long deadline, int redirects) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri);
        boolean ua = false;
        for (Map.Entry<String, String> h : r.headers().entrySet()) {
            b.header(h.getKey(), h.getValue());
            ua |= h.getKey().equalsIgnoreCase("user-agent");
        }
        if (!ua) {
            b.header("User-Agent", USER_AGENT);
        }
        b.method(r.method(), r.body() == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(r.body(), StandardCharsets.UTF_8));
        Capped capped = new Capped(r.method().equals("HEAD") ? 0 : settings.maxResponseBytes());
        CompletableFuture<HttpResponse<Capped>> f;
        try {
            f = client.get().sendAsync(b.build(), info -> capped);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        HttpResponse<Capped> resp;
        try {
            long wait = deadline - System.nanoTime();
            if (wait <= 0) {
                throw new TimeoutException();
            }
            resp = f.get(wait, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            f.cancel(true);
            Thread.currentThread().interrupt();
            throw new HttpException("cancelled");
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new HttpException("timed out after " + r.timeout().toMillis() + " ms");
        } catch (ExecutionException e) {
            throw failure(uri, e.getCause());
        }
        if (resp.statusCode() == 403 && resp.headers().firstValue(EgressProxy.DENIED).isPresent()) {
            throw blocked(uri);
        }
        String type = resp.headers().firstValue("content-type").orElse("").strip();
        boolean binary = !isText(type);
        String text = binary ? "" : decode(capped.bytes(), charset(type));
        return new Response(resp.statusCode(), uri, resp.headers(), type, text, capped.size(), capped.truncated(), binary, redirects);
    }

    private static HttpException blocked(URI uri) {
        return new HttpException("blocked by the network rules: " + uri.getHost() + " (see /network list)");
    }

    private static RuntimeException failure(URI uri, Throwable c) {
        String msg = c == null ? "" : String.valueOf(c.getMessage());
        // A refused CONNECT tunnel: the JDK reports the proxy's status in the message.
        if (c instanceof IOException && msg.contains("403")) {
            return blocked(uri);
        }
        if (c instanceof java.net.http.HttpTimeoutException) {
            return new HttpException("timed out connecting to " + uri.getHost());
        }
        if (c instanceof java.net.ConnectException) {
            return new HttpException("cannot connect to " + uri.getHost() + (msg.isEmpty() || msg.equals("null") ? "" : ": " + msg));
        }
        return new HttpException("request to " + uri.getHost() + " failed: " + (c == null ? "unknown error" : c.getClass().getSimpleName()
                + (msg.equals("null") ? "" : ": " + msg)));
    }

    private void completed(Request r, Response resp, long t0, String error) {
        ObjectNode o = Json.object().put("method", r.method()).put("url", resp != null ? resp.url().toString() : r.uri().toString())
                .put("status", resp != null ? resp.status() : 0)
                .put("contentType", resp != null ? resp.contentType() : "")
                .put("bytes", resp != null ? resp.bytes() : 0)
                .put("truncated", resp != null && resp.truncated())
                .put("durationMs", (System.nanoTime() - t0) / 1_000_000)
                .put("redirects", resp != null ? resp.redirects() : 0);
        if (error != null) {
            o.put("error", error);
        }
        if (resp != null && settings.logHeaders()) {
            logHeaders(o, headerMap(resp.headers()));
        }
        if (resp != null && settings.logBodies() && !resp.binary()) {
            o.put("body", resp.text());
        }
        bus.publish(origin.get(), "http.response", o);
    }

    private void logHeaders(ObjectNode event, Map<String, String> headers) {
        ObjectNode h = event.putObject("headers");
        headers.forEach((k, v) -> h.put(k, settings.redactSecrets() && secret(k) ? REDACTED : v));
    }

    /** A header whose value is likely a credential: logged as {@value #REDACTED} unless redaction is off. */
    static boolean secret(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return SECRET_HEADERS.contains(n) || !n.equals("www-authenticate") && SECRET_WORDS.stream().anyMatch(n::contains);
    }

    // --- Bodies ---------------------------------------------------------------------------------

    /** Text types (AC-05); a missing type counts as text. */
    static boolean isText(String contentType) {
        String t = contentType.toLowerCase(Locale.ROOT);
        int semi = t.indexOf(';');
        if (semi >= 0) {
            t = t.substring(0, semi);
        }
        t = t.strip();
        return t.isEmpty() || t.startsWith("text/") || t.equals("application/json") || t.equals("application/xml")
                || t.equals("application/javascript") || t.endsWith("+json") || t.endsWith("+xml");
    }

    static Charset charset(String contentType) {
        for (String part : contentType.split(";")) {
            String p = part.strip();
            if (p.regionMatches(true, 0, "charset=", 0, 8)) {
                String name = p.substring(8).replace("\"", "").strip();
                try {
                    return Charset.forName(name);
                } catch (IllegalArgumentException e) {
                    return StandardCharsets.UTF_8;
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    static String decode(byte[] bytes, Charset cs) {
        try {
            return cs.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /** Lowercase names; repeated headers joined with ", ". */
    static Map<String, String> headerMap(HttpHeaders h) {
        Map<String, String> m = new TreeMap<>();
        h.map().forEach((k, v) -> {
            if (!k.startsWith(":")) {
                m.merge(k.toLowerCase(Locale.ROOT), String.join(", ", v), (a, b) -> a + ", " + b);
            }
        });
        return m;
    }

    /** Reads a body up to {@code limit} bytes, then cancels the rest (AC-05). */
    static final class Capped implements HttpResponse.BodySubscriber<Capped> {
        private final long limit;
        private final List<byte[]> parts = new ArrayList<>();
        private final CompletableFuture<Capped> done = new CompletableFuture<>();
        private long size;
        private boolean truncated;
        private Flow.Subscription subscription;

        Capped(long limit) {
            this.limit = limit;
        }

        long size() {
            return size;
        }

        boolean truncated() {
            return truncated;
        }

        byte[] bytes() {
            byte[] all = new byte[(int) size];
            int at = 0;
            for (byte[] p : parts) {
                System.arraycopy(p, 0, all, at, p.length);
                at += p.length;
            }
            return all;
        }

        @Override
        public CompletionStage<Capped> getBody() {
            return done;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (done.isDone()) {
                return;
            }
            for (ByteBuffer b : items) {
                long room = limit - size;
                int n = (int) Math.min(room, b.remaining());
                if (n > 0) {
                    byte[] p = new byte[n];
                    b.get(p);
                    parts.add(p);
                    size += n;
                }
                if (b.hasRemaining()) {
                    truncated = true;
                }
            }
            if (truncated) {
                subscription.cancel();
                done.complete(this);
            }
        }

        @Override
        public void onError(Throwable t) {
            if (!done.isDone()) {
                done.completeExceptionally(t);
            }
        }

        @Override
        public void onComplete() {
            done.complete(this);
        }
    }
}
