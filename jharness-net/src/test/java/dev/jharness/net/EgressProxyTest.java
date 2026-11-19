package dev.jharness.net;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.events.Event;
import dev.jharness.events.EventBus;
import dev.jharness.events.Origin;
import dev.jharness.security.Operation;
import dev.jharness.security.Prompter;

class EgressProxyTest {

    @TempDir
    Path tmp;
    final EventBus bus = new EventBus("s-net");
    final List<Event> events = new CopyOnWriteArrayList<>();
    final List<AutoCloseable> closing = new ArrayList<>();
    EgressProxy proxy;

    @AfterEach
    void tearDown() throws Exception {
        for (AutoCloseable c : closing) {
            c.close();
        }
        if (proxy != null) {
            proxy.close();
        }
    }

    /** upstream.test and echo.test resolve to loopback; other names to a public address; bad.test fails. */
    static InetAddress[] resolve(String host) throws UnknownHostException {
        return switch (host) {
            case "upstream.test", "echo.test" -> new InetAddress[] {InetAddress.getLoopbackAddress()};
            case "rebind.example.com" -> new InetAddress[] {InetAddress.ofLiteral("93.184.216.34"), InetAddress.ofLiteral("10.0.0.7")};
            case "bad.test" -> throw new UnknownHostException(host);
            default -> new InetAddress[] {InetAddress.ofLiteral("93.184.216.34")};
        };
    }

    private EgressProxy start(String general, boolean trusted, Prompter prompter) throws Exception {
        Path g = tmp.resolve("home/.jharness/network.rules");
        Path p = tmp.resolve("ws/.jharness/network.rules");
        Files.createDirectories(g.getParent());
        Files.writeString(g, general);
        bus.subscribe(events::add);
        proxy = EgressProxy.start(() -> new NetworkRules(RulesFile.load(g), RulesFile.load(p), trusted, List.of()), prompter, bus,
                () -> Origin.main(1), p, EgressProxyTest::resolve);
        return proxy;
    }

    private String auth() {
        var c = proxy.credentials();
        return "Proxy-Authorization: Basic " + Base64.getEncoder().encodeToString((c.getUserName() + ":" + new String(c.getPassword()))
                .getBytes(StandardCharsets.UTF_8)) + "\r\n";
    }

    private Socket client() throws IOException {
        Socket s = new Socket(proxy.address().getAddress(), proxy.address().getPort());
        s.setSoTimeout(10_000);
        closing.add(s);
        return s;
    }

    private static String readAll(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
    }

    private List<String> types() {
        return events.stream().map(Event::type).filter(t -> t.startsWith("network.")).toList();
    }

    /** A one-shot HTTP upstream that records the request and answers with the given parts. */
    final class Upstream implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        final List<String> requests = new CopyOnWriteArrayList<>();
        final CountDownLatch firstPartRead = new CountDownLatch(1);

        Upstream(String... parts) throws IOException {
            closing.add(this);
            Thread.ofVirtual().start(() -> {
                try (Socket s = server.accept()) {
                    InputStream in = s.getInputStream();
                    BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1));
                    StringBuilder req = new StringBuilder();
                    String line;
                    int length = 0;
                    while ((line = r.readLine()) != null && !line.isEmpty()) {
                        req.append(line).append('\n');
                        if (line.toLowerCase().startsWith("content-length:")) {
                            length = Integer.parseInt(line.substring(15).strip());
                        }
                    }
                    char[] body = new char[length];
                    int got = 0;
                    while (got < length) {
                        got += r.read(body, got, length - got);
                    }
                    req.append('\n').append(body);
                    requests.add(req.toString());
                    OutputStream out = s.getOutputStream();
                    for (int i = 0; i < parts.length; i++) {
                        out.write(parts[i].getBytes(StandardCharsets.ISO_8859_1));
                        out.flush();
                        if (i == 0 && parts.length > 1) {
                            firstPartRead.await(5, TimeUnit.SECONDS);
                        }
                    }
                } catch (Exception e) {
                    // test ends
                }
            });
        }

        int port() {
            return server.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    @Test
    void requiresTheSessionCredentials() throws Exception {
        start("", true, null);
        for (String header : List.of("", "Proxy-Authorization: Basic " + Base64.getEncoder().encodeToString("jharness:wrong".getBytes()) + "\r\n")) {
            Socket c = client();
            c.getOutputStream().write(("CONNECT echo.test:443 HTTP/1.1\r\nHost: echo.test:443\r\n" + header + "\r\n").getBytes());
            String resp = readAll(c.getInputStream());
            assertTrue(resp.startsWith("HTTP/1.1 407"), resp);
            assertTrue(resp.contains("Proxy-Authenticate: Basic realm=\"jharness\""), resp);
        }
        assertTrue(types().isEmpty(), "no decision without credentials: " + types());
        assertEquals("127.0.0.1", proxy.address().getAddress().getHostAddress());
    }

    @Test
    void plainHttpIsRelayedAsItArrives() throws Exception {
        Upstream up = new Upstream("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: keep-alive\r\n\r\ndata: one\n\n",
                "data: two\n\n");
        start("allow upstream.test:" + up.port(), true, null);
        Socket c = client();
        String body = "{\"q\":1}";
        c.getOutputStream().write(("POST http://upstream.test:" + up.port() + "/v1/chat?x=1 HTTP/1.1\r\nHost: upstream.test:" + up.port()
                + "\r\n" + auth() + "Connection: keep-alive\r\nContent-Length: " + body.length() + "\r\n\r\n" + body).getBytes());
        InputStream in = c.getInputStream();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1));
        List<String> head = new ArrayList<>();
        String line;
        while (!(line = r.readLine()).isEmpty()) {
            head.add(line);
        }
        assertEquals("HTTP/1.1 200 OK", head.getFirst());
        assertTrue(head.contains("Connection: close"), head.toString());
        assertFalse(head.contains("Connection: keep-alive"), head.toString());
        assertEquals("data: one", r.readLine(), "the first part arrives before the upstream sends the second");
        up.firstPartRead.countDown();
        assertEquals("", r.readLine());
        assertEquals("data: two", r.readLine());
        String req = up.requests.getFirst();
        assertTrue(req.startsWith("POST /v1/chat?x=1 HTTP/1.1\n"), req);
        assertFalse(req.toLowerCase().contains("proxy-authorization"), req);
        assertTrue(req.contains("Connection: close") && !req.contains("keep-alive"), req);
        assertTrue(req.endsWith("\n" + body), req);
        Event allowed = events.stream().filter(e -> e.type().equals("network.allowed")).findFirst().orElseThrow();
        assertEquals("upstream.test", allowed.body().get("host").asText());
        assertEquals("127.0.0.1", allowed.body().get("ip").asText());
        assertTrue(allowed.body().get("reason").asText().startsWith("allow upstream.test:" + up.port()), allowed.body().toString());
    }

    @Test
    void connectTunnelsBytesBothWays() throws Exception {
        ServerSocket echo = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        closing.add(echo);
        Thread.ofVirtual().start(() -> {
            try (Socket s = echo.accept()) {
                s.getInputStream().transferTo(s.getOutputStream());
            } catch (IOException e) {
                // done
            }
        });
        start("allow echo.test", true, null);
        Socket c = client();
        OutputStream out = c.getOutputStream();
        out.write(("CONNECT echo.test:" + echo.getLocalPort() + " HTTP/1.1\r\nHost: echo.test\r\n" + auth() + "\r\n").getBytes());
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.ISO_8859_1));
        assertEquals("HTTP/1.1 200 Connection Established", r.readLine());
        assertEquals("", r.readLine());
        out.write("\u0016\u0003\u0001 binary-ish hello\n".getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        assertEquals("\u0016\u0003\u0001 binary-ish hello", r.readLine());
        assertEquals(List.of("network.allowed"), types());
    }

    @Test
    void blockedHostsGet403AndAnEvent() throws Exception {
        start("default deny\nallow *.example.com", true, null);
        for (String target : List.of("CONNECT evil.test:443", "GET http://evil.test/x", "CONNECT rebind.example.com:443", "CONNECT bad.test:443",
                "CONNECT upstream.test:80")) {
            Socket c = client();
            c.getOutputStream().write((target + " HTTP/1.1\r\nHost: x\r\n" + auth() + "\r\n").getBytes());
            String resp = readAll(c.getInputStream());
            assertTrue(resp.startsWith("HTTP/1.1 403"), target + ": " + resp);
        }
        List<String> reasons = events.stream().filter(e -> e.type().equals("network.blocked")).map(e -> e.body().get("reason").asText()).toList();
        assertEquals(5, reasons.size(), reasons.toString());
        assertTrue(reasons.get(0).startsWith("default deny"), reasons.get(0));
        assertTrue(reasons.get(2).startsWith("built-in deny"), "DNS rebinding to a private address: " + reasons.get(2));
        assertEquals("cannot resolve bad.test", reasons.get(3));
        assertTrue(reasons.get(4).startsWith("built-in deny"), "loopback without an explicit allow: " + reasons.get(4));
        assertTrue(types().stream().noneMatch(t -> t.equals("network.allowed")));
    }

    @Test
    void badRequests() throws Exception {
        start("allow upstream.test", true, null);
        for (String req : List.of("GET /relative HTTP/1.1\r\n", "CONNECT nohost HTTP/1.1\r\n", "garbage\r\n",
                "POST http://upstream.test/ HTTP/1.1\r\nTransfer-Encoding: chunked\r\n")) {
            Socket c = client();
            c.getOutputStream().write((req + auth() + "\r\n").getBytes());
            String resp = readAll(c.getInputStream());
            assertTrue(resp.startsWith("HTTP/1.1 400") || resp.startsWith("HTTP/1.1 411"), req + ": " + resp);
        }
    }

    // --- Ask (AC-06); check() decides without connecting ---------------------------------------

    @Test
    void onceAllowsForTheSession() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        List<Operation> ops = new CopyOnWriteArrayList<>();
        start("", true, op -> {
            asked.incrementAndGet();
            ops.add(op);
            return Prompter.Answer.ONCE;
        });
        assertNotNull(proxy.check("api.example.com", 443));
        assertNotNull(proxy.check("api.example.com", 443));
        assertEquals(1, asked.get(), "asked once per session");
        assertEquals(Operation.Kind.NETWORK, ops.getFirst().kind());
        assertEquals("connect api.example.com:443", ops.getFirst().summary());
        assertEquals(List.of("network.asked", "network.allowed", "network.allowed"), types());
        assertEquals(java.util.Set.of("api.example.com:443"), proxy.sessionAllows());
        assertFalse(Files.exists(tmp.resolve("ws/.jharness/network.rules")), "once doesn't write the file");
    }

    @Test
    void alwaysAppendsToTheProjectFile() throws Exception {
        start("", true, op -> Prompter.Answer.ALWAYS);
        Files.createDirectories(tmp.resolve("ws/.jharness"));
        Files.writeString(tmp.resolve("ws/.jharness/network.rules"), "deny tracker.example.com");
        assertNotNull(proxy.check("API.example.com", 443));
        assertEquals("deny tracker.example.com\nallow api.example.com:443\n", Files.readString(tmp.resolve("ws/.jharness/network.rules")));
        assertEquals(Rule.Action.ALLOW, proxy.rules().decide("api.example.com", 443, InetAddress.ofLiteral("93.184.216.34")).action(),
                "reloaded: the new rule decides now");
    }

    @Test
    void alwaysInAnUntrustedProjectIsWrittenButInactive() throws Exception {
        start("", false, op -> Prompter.Answer.ALWAYS);
        assertNotNull(proxy.check("api.example.com", 443), "allowed for this session");
        assertEquals("allow api.example.com:443\n", Files.readString(tmp.resolve("ws/.jharness/network.rules")));
        assertEquals(Rule.Action.ASK, proxy.rules().decide("api.example.com", 443, InetAddress.ofLiteral("93.184.216.34")).action(),
                "the line counts only once the project is trusted (D-047)");
    }

    @Test
    void denyAndHeadlessBlock() throws Exception {
        start("", true, op -> Prompter.Answer.DENY);
        assertNull(proxy.check("api.example.com", 443));
        proxy.close();
        events.clear();
        proxy = EgressProxy.start(() -> new NetworkRules(RulesFile.empty(tmp.resolve("g")), RulesFile.empty(tmp.resolve("p")), true, List.of()),
                null, bus, () -> Origin.main(1), tmp.resolve("p"), EgressProxyTest::resolve);
        assertNull(proxy.check("api.example.com", 443), "no prompter (headless) blocks");
        Event b = events.stream().filter(e -> e.type().equals("network.blocked")).findFirst().orElseThrow();
        assertEquals("denied by the user", b.body().get("reason").asText());
    }

    @Test
    void hostPortParsing() {
        assertEquals(new EgressProxy.HostPort("example.com", 443), EgressProxy.HostPort.parse("example.com:443", -1));
        assertEquals(new EgressProxy.HostPort("::1", 8443), EgressProxy.HostPort.parse("[::1]:8443", -1));
        assertNull(EgressProxy.HostPort.parse("example.com", -1));
        assertEquals(new EgressProxy.HostPort("example.com", 80), EgressProxy.HostPort.parse("example.com", 80));
        assertNull(EgressProxy.HostPort.parse("example.com:0", -1));
        assertNull(EgressProxy.HostPort.parse(":443", -1));
        assertEquals("[::1]:80", EgressProxy.key("::1", 80));
    }
}
