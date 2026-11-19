package dev.jharness.process;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.events.Event;
import dev.jharness.events.EventBus;
import dev.jharness.events.Origin;
import dev.jharness.net.EgressProxy;
import dev.jharness.net.NetworkRules;
import dev.jharness.net.Rule;
import dev.jharness.net.RulesFile;

/** Spec 013 AC-07: a sandboxed child reaches the network only through the egress proxy's port. */
class ProxyNetworkTest {

    static final Path CURL = Path.of("/usr/bin/curl");

    @TempDir
    Path tmp;
    Path ws;
    ServerSocket stub;
    EgressProxy proxy;
    ChildSandbox sandbox;
    EventBus bus;
    final List<Event> events = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(Files.isExecutable(CURL), "curl is not installed");
        ws = Files.createDirectories(tmp.resolve("ws")).toRealPath();
        stub = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(this::serve);
        bus = new EventBus("s-net");
        bus.subscribe(events::add);
        List<Rule> allow = List.of(RulesFile.rule(Rule.Action.ALLOW, "127.0.0.1:" + stub.getLocalPort(), "test"));
        proxy = EgressProxy.start(() -> new NetworkRules(RulesFile.empty(tmp.resolve("g")), RulesFile.empty(tmp.resolve("p")), true, allow),
                null, bus, () -> Origin.main(1), tmp.resolve("p"));
        // Unix socket paths are short (104 bytes on macOS); @TempDir paths can be long.
        Path sock = Files.createTempDirectory("jh-sock-").resolve("proxy.sock");
        ChildSandbox base = TestSandboxes.in(ws);
        assumeTrue(base.available(), "no OS sandbox here: " + base.reason());
        if (base.mechanism() == ChildSandbox.Mechanism.BUBBLEWRAP) {
            proxy.serveUnix(sock);
        }
        var cred = proxy.credentials();
        sandbox = base.withProxy(new ChildSandbox.ProxyRoute(proxy.address().getPort(), sock, cred.getUserName(), new String(cred.getPassword())),
                ChildSandbox.selfBridgeCommand());
    }

    @AfterEach
    void tearDown() throws IOException {
        if (proxy != null) {
            proxy.close();
        }
        if (stub != null) {
            stub.close();
        }
    }

    /** Answers every request with "hello from stub". */
    private void serve() {
        while (!stub.isClosed()) {
            try (Socket c = stub.accept()) {
                InputStream in = c.getInputStream();
                StringBuilder head = new StringBuilder();
                int b;
                while ((b = in.read()) >= 0) {
                    head.append((char) b);
                    if (head.toString().endsWith("\r\n\r\n")) {
                        break;
                    }
                }
                OutputStream out = c.getOutputStream();
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 15\r\nConnection: close\r\n\r\nhello from stub".getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } catch (IOException e) {
                // closed
            }
        }
    }

    private ProcessResult curl(ChildSandbox.Grants g, String... args) {
        List<String> a = new ArrayList<>(List.of("-sS", "--max-time", "8"));
        a.addAll(List.of(args));
        return new ProcessRunner(bus, () -> Origin.main(1), 30_000, sandbox).run(new Executable("curl", CURL), a, ws,
                Duration.ofSeconds(30), Map.of(), g);
    }

    private String proxyUrl() {
        var c = proxy.credentials();
        return "http://" + c.getUserName() + ":" + new String(c.getPassword()) + "@127.0.0.1:" + proxy.address().getPort();
    }

    private String target() {
        return "http://127.0.0.1:" + stub.getLocalPort() + "/";
    }

    @Test
    void reachesAnAllowedHostThroughTheProxy() {
        ProcessResult plain = curl(new ChildSandbox.Grants(List.of(), true), "-x", proxyUrl(), target());
        assertEquals(0, plain.exitCode(), plain.output());
        assertEquals("hello from stub", plain.output());
        ProcessResult tunnel = curl(new ChildSandbox.Grants(List.of(), true), "-p", "-x", proxyUrl(), target());
        assertEquals(0, tunnel.exitCode(), tunnel.output());
        assertEquals("hello from stub", tunnel.output());
        assertTrue(events.stream().anyMatch(e -> e.type().equals("network.allowed")), events.toString());
        assertTrue(events.stream().anyMatch(e -> e.type().equals("process.started") && e.body().path("network").asBoolean()));
    }

    @Test
    void directConnectionsFailEvenWithNetwork() {
        ProcessResult r = curl(new ChildSandbox.Grants(List.of(), true), target());
        assertNotEquals(0, r.exitCode(), r.output());
        assertFalse(r.output().contains("hello from stub"), r.output());
    }

    @Test
    void theProxyStillAppliesTheRules() {
        // Same proxy, but the target port has no allow rule: the built-in loopback deny blocks it.
        ProcessResult r = curl(new ChildSandbox.Grants(List.of(), true), "-x", proxyUrl(), "http://127.0.0.1:9/");
        assertFalse(r.output().contains("hello from stub"), r.output());
        assertTrue(events.stream().anyMatch(e -> e.type().equals("network.blocked")), events.toString());
    }

    @Test
    void withoutTheGrantThereIsNoNetwork() {
        ProcessResult r = curl(ChildSandbox.Grants.NONE, "-x", proxyUrl(), target());
        assertNotEquals(0, r.exitCode(), r.output());
        assertFalse(r.output().contains("hello from stub"), r.output());
    }

    @Test
    void theProxyPasswordIsRedactedFromOutput() {
        String pass = new String(proxy.credentials().getPassword());
        ProcessResult r = curl(new ChildSandbox.Grants(List.of(), true), "-x", proxyUrl(), "-w", "%{url_effective} " + pass, target());
        assertFalse(r.output().contains(pass), r.output());
        assertFalse(r.full().contains(pass), r.full());
        assertTrue(r.output().contains("***"), r.output());
    }
}
