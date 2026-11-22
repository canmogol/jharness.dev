package dev.jharness.net.http;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.jharness.config.HttpSettings;
import dev.jharness.config.Timeouts;
import dev.jharness.events.Event;
import dev.jharness.events.EventBus;
import dev.jharness.events.Json;
import dev.jharness.events.Origin;
import dev.jharness.net.EgressProxy;
import dev.jharness.net.NetworkRules;
import dev.jharness.net.RulesFile;
import dev.jharness.security.Operation;
import dev.jharness.security.Prompter;

/**
 * {@code api.http} and {@code api.fetch} against a local stub, always through the real egress proxy
 * (spec 016). {@code site.test} resolves to the stub on loopback and is allowed by a rule; everything
 * else resolves to a public address and is denied.
 */
class HttpOperationsTest {

    @TempDir
    Path tmp;
    final EventBus bus = new EventBus("s-http");
    final List<Event> events = new CopyOnWriteArrayList<>();
    HttpServer server;
    EgressProxy proxy;
    int port;
    HttpOperations ops;

    static InetAddress[] resolve(String host) throws UnknownHostException {
        return host.equals("site.test") ? new InetAddress[] {InetAddress.getLoopbackAddress()}
                : new InetAddress[] {InetAddress.ofLiteral("93.184.216.34")};
    }

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/page", x -> reply(x, 200, "text/html; charset=utf-8",
                "<html><head><title>Guide</title><script>steal()</script></head><body><nav>menu</nav><h1>Intro</h1>"
                        + "<p>Read <a href=\"/docs\">the docs</a>.</p><ul><li>one</li><li>two</li></ul></body></html>"));
        server.createContext("/json", x -> {
            x.getResponseHeaders().add("X-Multi", "a");
            x.getResponseHeaders().add("X-Multi", "b");
            reply(x, 200, "application/json", "{\"ok\":true}");
        });
        server.createContext("/latin1", x -> reply(x, 200, "text/plain; charset=ISO-8859-1", "café", StandardCharsets.ISO_8859_1));
        server.createContext("/bin", x -> reply(x, 200, "application/octet-stream", "\u0000\u0001binary"));
        server.createContext("/big", x -> reply(x, 200, "text/plain", "x".repeat(200_000)));
        server.createContext("/huge", x -> reply(x, 200, "text/plain", "y".repeat(300 * 1024)));
        server.createContext("/missing", x -> reply(x, 404, "text/html", "<title>Not here</title><p>gone</p>"));
        server.createContext("/redirect", x -> {
            x.getResponseHeaders().add("Location", "/page");
            reply(x, 302, "text/plain", "");
        });
        server.createContext("/loop", x -> {
            x.getResponseHeaders().add("Location", "/loop");
            reply(x, 302, "text/plain", "");
        });
        server.createContext("/to-denied", x -> {
            x.getResponseHeaders().add("Location", "http://denied.test/");
            reply(x, 302, "text/plain", "");
        });
        server.createContext("/slow", x -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            reply(x, 200, "text/plain", "late");
        });
        server.createContext("/echo", x -> {
            String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String ua = x.getRequestHeaders().getFirst("User-Agent");
            reply(x, 201, "text/plain", x.getRequestMethod() + "|" + body + "|" + x.getRequestHeaders().getFirst("X-Test") + "|" + ua);
        });
        server.start();

        Path g = tmp.resolve("home/.jharness/network.rules");
        Files.createDirectories(g.getParent());
        Files.writeString(g, "default deny\nallow site.test\n");
        bus.subscribe(events::add);
        proxy = EgressProxy.start(() -> new NetworkRules(RulesFile.load(g), RulesFile.load(tmp.resolve("ws/.jharness/network.rules")),
                true, List.of()), Prompter.none(), bus, () -> Origin.main(1), tmp.resolve("ws/.jharness/network.rules"),
                HttpOperationsTest::resolve);
        HttpClients.useProxy(proxy.address(), proxy.credentials());
        ops = new HttpOperations(bus, () -> Origin.main(1), new HttpSettings(256 * 1024, 100_000, false, false, true), Timeouts.defaults());
    }

    @AfterEach
    void tearDown() {
        HttpClients.stopProxy(proxy.address());
        proxy.close();
        server.stop(0);
    }

    private static void reply(HttpExchange x, int code, String type, String body) throws IOException {
        reply(x, code, type, body, StandardCharsets.UTF_8);
    }

    private static void reply(HttpExchange x, int code, String type, String body, java.nio.charset.Charset cs) throws IOException {
        byte[] b = body.getBytes(cs);
        x.getResponseHeaders().add("Content-Type", type);
        x.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
        try (OutputStream o = x.getResponseBody()) {
            o.write(b);
        }
    }

    private String url(String path) {
        return "http://site.test:" + port + path;
    }

    private ObjectNode http(String json) {
        return ops.http(ops.check(Json.parseObject(json)));
    }

    private ObjectNode fetch(String url) {
        return ops.fetch(ops.checkFetch(Json.parseObject("{\"url\":\"" + url + "\"}")));
    }

    // --- AC-01, AC-02: checks ---

    @Test
    void requestsAreChecked() {
        String u = url("/json");
        for (String bad : List.of("{}", "{\"url\":\"ftp://x/\"}", "{\"url\":\"file:///etc/passwd\"}", "{\"url\":\"/relative\"}",
                "{\"url\":\"http://user:pw@site.test/\"}", "{\"url\":\"" + u + "\",\"method\":\"TRACE\"}",
                "{\"url\":\"" + u + "\",\"body\":\"x\"}", "{\"url\":\"" + u + "\",\"method\":\"POST\",\"body\":{}}",
                "{\"url\":\"" + u + "\",\"extra\":1}", "{\"url\":\"" + u + "\",\"timeoutMs\":999999999}",
                "{\"url\":\"http://" + "a".repeat(9000) + "/\"}")) {
            assertThrows(IllegalArgumentException.class, () -> ops.check(Json.parseObject(bad)), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> ops.checkFetch(Json.parseObject("{\"url\":\"" + u + "\",\"method\":\"GET\"}")));
        assertEquals("POST", ops.check(Json.parseObject("{\"url\":\"" + u + "\",\"method\":\"post\",\"body\":\"x\"}")).method());
    }

    @Test
    void headersAreChecked() {
        for (String h : List.of("Host", "content-length", "Connection", "Transfer-Encoding", "Upgrade", "Expect", "TE",
                "Proxy-Authorization", "proxy-connection", "bad name", "")) {
            ObjectNode r = Json.object().put("url", url("/json"));
            r.putObject("headers").put(h, "x");
            assertThrows(IllegalArgumentException.class, () -> ops.check(r), h);
        }
        ObjectNode crlf = Json.object().put("url", url("/json"));
        crlf.putObject("headers").put("X-A", "a\r\nX-Injected: 1");
        assertThrows(IllegalArgumentException.class, () -> ops.check(crlf));
        ObjectNode many = Json.object().put("url", url("/json"));
        for (int i = 0; i < 51; i++) {
            many.with("headers").put("X-" + i, "v");
        }
        assertThrows(IllegalArgumentException.class, () -> ops.check(many));
        ObjectNode ok = Json.object().put("url", url("/json"));
        ok.putObject("headers").put("Authorization", "Bearer abc").put("Accept", "application/json");
        assertEquals(2, ops.check(ok).headers().size());
    }

    // --- AC-04: kinds and rule keys ---

    @Test
    void kindsAndRuleKeys() {
        assertEquals(Operation.Kind.HTTP, ops.check(Json.parseObject("{\"url\":\"" + url("/") + "\"}")).kind());
        assertEquals(Operation.Kind.HTTP, ops.check(Json.parseObject("{\"url\":\"" + url("/") + "\",\"method\":\"HEAD\"}")).kind());
        for (String m : List.of("POST", "PUT", "PATCH", "DELETE")) {
            var r = ops.check(Json.parseObject("{\"url\":\"http://API.Example.com/x\",\"method\":\"" + m + "\"}"));
            assertEquals(Operation.Kind.HTTP_WRITE, r.kind());
            assertEquals(m + " api.example.com", r.detail().get("op").asText());
            assertTrue(r.kind().changesState());
        }
        assertEquals(Operation.Kind.HTTP, ops.checkFetch(Json.parseObject("{\"url\":\"" + url("/") + "\"}")).kind());
    }

    // --- AC-03, AC-05: sending through the proxy ---

    @Test
    void getReturnsTextAndHeaders() {
        ObjectNode r = http("{\"url\":\"" + url("/json") + "\"}");
        assertEquals(200, r.get("status").asInt());
        assertEquals("{\"ok\":true}", r.get("body").asText());
        assertEquals("application/json", r.get("contentType").asText());
        assertEquals("a, b", r.get("headers").get("x-multi").asText());
        assertFalse(r.get("binary").asBoolean());
        assertFalse(r.get("truncated").asBoolean());
    }

    @Test
    void postSendsBodyHeadersAndUserAgent() {
        ObjectNode r = http("{\"url\":\"" + url("/echo") + "\",\"method\":\"POST\",\"body\":\"hello\",\"headers\":{\"X-Test\":\"t\"}}");
        assertEquals(201, r.get("status").asInt());
        assertEquals("POST|hello|t|" + HttpOperations.USER_AGENT, r.get("body").asText());
        ObjectNode own = http("{\"url\":\"" + url("/echo") + "\",\"method\":\"PUT\",\"headers\":{\"User-Agent\":\"mine\"}}");
        assertTrue(own.get("body").asText().endsWith("|mine"), own.toString());
    }

    @Test
    void charsetsBinaryAndCaps() {
        assertEquals("café", http("{\"url\":\"" + url("/latin1") + "\"}").get("body").asText());
        ObjectNode bin = http("{\"url\":\"" + url("/bin") + "\"}");
        assertTrue(bin.get("binary").asBoolean());
        assertEquals("", bin.get("body").asText());
        assertEquals(8, bin.get("bytes").asInt());
        ObjectNode big = http("{\"url\":\"" + url("/big") + "\"}");
        assertEquals(100_000, big.get("body").asText().length(), "maxChars");
        assertTrue(big.get("truncated").asBoolean());
        ObjectNode huge = http("{\"url\":\"" + url("/huge") + "\"}");
        assertEquals(256 * 1024, huge.get("bytes").asInt(), "maxResponseBytes");
        assertTrue(huge.get("truncated").asBoolean());
        ObjectNode head = http("{\"url\":\"" + url("/json") + "\",\"method\":\"HEAD\"}");
        assertEquals(200, head.get("status").asInt());
        assertEquals("", head.get("body").asText());
    }

    @Test
    void redirectsAreFollowedForReadsOnly() {
        ObjectNode r = http("{\"url\":\"" + url("/redirect") + "\"}");
        assertEquals(200, r.get("status").asInt());
        assertEquals(url("/page"), r.get("url").asText());
        ObjectNode post = http("{\"url\":\"" + url("/redirect") + "\",\"method\":\"POST\",\"body\":\"x\"}");
        assertEquals(302, post.get("status").asInt());
        assertEquals("/page", post.get("headers").get("location").asText());
        HttpException loop = assertThrows(HttpException.class, () -> http("{\"url\":\"" + url("/loop") + "\"}"));
        assertTrue(loop.getMessage().contains("more than 5 redirects"), loop.getMessage());
        var last = events.stream().filter(e -> e.type().equals("http.response")).reduce((a, b) -> b).orElseThrow();
        assertEquals(0, last.body().get("status").asInt());
        assertTrue(last.body().has("error"));
    }

    @Test
    void deniedHostsFailAtTheProxy() {
        HttpException plain = assertThrows(HttpException.class, () -> http("{\"url\":\"http://denied.test/\"}"));
        assertTrue(plain.getMessage().startsWith("blocked by the network rules: denied.test"), plain.getMessage());
        HttpException tls = assertThrows(HttpException.class, () -> http("{\"url\":\"https://denied.test/\"}"));
        assertTrue(tls.getMessage().startsWith("blocked by the network rules: denied.test"), tls.getMessage());
        HttpException hop = assertThrows(HttpException.class, () -> http("{\"url\":\"" + url("/to-denied") + "\"}"));
        assertTrue(hop.getMessage().contains("denied.test"), hop.getMessage());
        assertTrue(events.stream().anyMatch(e -> e.type().equals("network.blocked")));
    }

    @Test
    void noProxyMeansNoRequest() {
        HttpClients.stopProxy(proxy.address());
        HttpException e = assertThrows(HttpException.class, () -> http("{\"url\":\"" + url("/json") + "\"}"));
        assertTrue(e.getMessage().contains("no egress proxy"), e.getMessage());
    }

    // --- AC-07: timeouts and cancel ---

    @Test
    void timeoutsAndCancel() throws Exception {
        long t0 = System.nanoTime();
        HttpException e = assertThrows(HttpException.class, () -> http("{\"url\":\"" + url("/slow") + "\",\"timeoutMs\":300}"));
        assertEquals("timed out after 300 ms", e.getMessage());
        assertTrue(Duration.ofNanos(System.nanoTime() - t0).toMillis() < 2500);

        var result = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread t = Thread.ofPlatform().start(() -> {
            try {
                http("{\"url\":\"" + url("/slow") + "\"}");
            } catch (Throwable x) {
                result.set(x);
            }
        });
        Thread.sleep(300);
        t.interrupt();
        t.join(2000);
        assertFalse(t.isAlive());
        assertEquals("cancelled", result.get().getMessage());
    }

    // --- AC-06: fetch ---

    @Test
    void fetchReturnsPageText() {
        ObjectNode p = fetch(url("/redirect"));
        assertEquals(200, p.get("status").asInt());
        assertEquals("Guide", p.get("title").asText());
        String text = p.get("text").asText();
        assertTrue(text.startsWith("# Intro"), text);
        assertTrue(text.contains("[the docs](" + url("/docs") + ")"), text);
        assertTrue(text.contains("- one\n- two"), text);
        assertFalse(text.contains("steal") || text.contains("menu"), text);
        assertEquals(url("/page"), p.get("url").asText());
    }

    @Test
    void fetchOfOtherTypes() {
        assertEquals("{\"ok\":true}", fetch(url("/json")).get("text").asText());
        ObjectNode missing = fetch(url("/missing"));
        assertEquals(404, missing.get("status").asInt());
        assertEquals("gone", missing.get("text").asText());
        HttpException bin = assertThrows(HttpException.class, () -> fetch(url("/bin")));
        assertEquals("not a text page (application/octet-stream)", bin.getMessage());
    }

    // --- AC-08: events ---

    @Test
    void eventsCarryNoHeadersOrBodies() {
        http("{\"url\":\"" + url("/echo") + "\",\"method\":\"POST\",\"body\":\"secret-body\",\"headers\":{\"Authorization\":\"Bearer tok\"}}");
        var req = events.stream().filter(e -> e.type().equals("http.request")).findFirst().orElseThrow();
        var resp = events.stream().filter(e -> e.type().equals("http.response")).findFirst().orElseThrow();
        assertEquals("POST", req.body().get("method").asText());
        assertEquals(201, resp.body().get("status").asInt());
        for (String f : List.of("contentType", "bytes", "truncated", "durationMs", "redirects")) {
            assertTrue(resp.body().has(f), f);
        }
        String all = events.toString() + req.body() + resp.body();
        assertFalse(all.contains("secret-body") || all.contains("Bearer tok"), all);
    }

    private ObjectNode debugEvents(boolean headers, boolean bodies, boolean redact, String json) {
        var debug = new HttpOperations(bus, () -> Origin.main(1), new HttpSettings(256 * 1024, 100_000, headers, bodies, redact),
                Timeouts.defaults());
        debug.http(debug.check(Json.parseObject(json)));
        var req = events.stream().filter(e -> e.type().equals("http.request")).findFirst().orElseThrow();
        var resp = events.stream().filter(e -> e.type().equals("http.response")).findFirst().orElseThrow();
        return Json.object().<ObjectNode>set("req", req.body()).set("resp", resp.body());
    }

    private String post() {
        return "{\"url\":\"" + url("/echo") + "\",\"method\":\"POST\",\"body\":\"secret-body\","
                + "\"headers\":{\"Authorization\":\"Bearer tok\",\"X-Api-Key\":\"k1\",\"X-Test\":\"visible\"}}";
    }

    @Test
    void debugLoggingAddsHeadersAndBodiesWithSecretsRedacted() {
        var ev = debugEvents(true, true, true, post());
        var req = ev.get("req");
        var resp = ev.get("resp");
        assertEquals("[redacted]", req.get("headers").get("authorization").asText());
        assertEquals("[redacted]", req.get("headers").get("x-api-key").asText());
        assertEquals("visible", req.get("headers").get("x-test").asText());
        assertEquals(HttpOperations.USER_AGENT, req.get("headers").get("user-agent").asText());
        assertEquals("secret-body", req.get("body").asText());
        assertEquals("text/plain", resp.get("headers").get("content-type").asText());
        assertTrue(resp.get("body").asText().startsWith("POST|secret-body|visible|"), resp.toString());
        assertFalse(ev.toString().contains("Bearer tok") || ev.toString().contains("k1\""), ev.toString());
    }

    @Test
    void debugSwitchesAreSeparateAndRedactionCanBeTurnedOff() {
        var headersOnly = debugEvents(true, false, false, post());
        assertEquals("Bearer tok", headersOnly.get("req").get("headers").get("authorization").asText());
        assertFalse(headersOnly.get("req").has("body") || headersOnly.get("resp").has("body"));
        events.clear();
        var bodiesOnly = debugEvents(false, true, true, post());
        assertFalse(bodiesOnly.get("req").has("headers") || bodiesOnly.get("resp").has("headers"));
        assertEquals("secret-body", bodiesOnly.get("req").get("body").asText());
        events.clear();
        var bin = debugEvents(false, true, true, "{\"url\":\"" + url("/bin") + "\"}");
        assertFalse(bin.get("resp").has("body"), "binary bodies log only their size");
    }

    @Test
    void secretHeaderNames() {
        for (String n : List.of("Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie", "X-Api-Key", "X-Auth-Token",
                "Private-Token", "X-Client-Secret")) {
            assertTrue(HttpOperations.secret(n), n);
        }
        for (String n : List.of("Accept", "Content-Type", "User-Agent", "WWW-Authenticate", "X-Test")) {
            assertFalse(HttpOperations.secret(n), n);
        }
    }
}
