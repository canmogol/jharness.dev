package dev.jharness.process;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

/** Spec 013 AC-07: a sandboxed child reaches the network only through the egress proxy's port. */
class ProxyNetworkTest {

    static final Path CURL = Path.of("/usr/bin/curl");

    @TempDir
    Path tmp;
    Path ws;
    TestProxy net;
    EventBus bus;
    final List<Event> events = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(Files.isExecutable(CURL), "curl is not installed");
        ws = Files.createDirectories(tmp.resolve("ws")).toRealPath();
        bus = new EventBus("s-net");
        bus.subscribe(events::add);
        net = new TestProxy(ws, bus, tmp);
        assumeTrue(net.sandbox.available(), "no OS sandbox here: " + net.sandbox.reason());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (net != null) {
            net.close();
        }
    }

    private ProcessResult curl(ChildSandbox.Grants g, String... args) {
        List<String> a = new ArrayList<>(List.of("-sS", "--max-time", "8"));
        a.addAll(List.of(args));
        return new ProcessRunner(bus, () -> Origin.main(1), 30_000, net.sandbox).run(new Executable("curl", CURL), a, ws,
                Duration.ofSeconds(30), Map.of(), g);
    }

    private String target() {
        return "http://127.0.0.1:" + net.stubPort() + "/";
    }

    @Test
    void reachesAnAllowedHostThroughTheProxy() {
        ProcessResult plain = curl(new ChildSandbox.Grants(List.of(), true), "-x", net.proxyUrl(), target());
        assertEquals(0, plain.exitCode(), plain.output());
        assertEquals(TestProxy.BODY, plain.output());
        ProcessResult tunnel = curl(new ChildSandbox.Grants(List.of(), true), "-p", "-x", net.proxyUrl(), target());
        assertEquals(0, tunnel.exitCode(), tunnel.output());
        assertEquals(TestProxy.BODY, tunnel.output());
        assertTrue(events.stream().anyMatch(e -> e.type().equals("network.allowed")), events.toString());
        assertTrue(events.stream().anyMatch(e -> e.type().equals("process.started") && e.body().path("network").asBoolean()));
    }

    @Test
    void directConnectionsFailEvenWithNetwork() {
        ProcessResult r = curl(new ChildSandbox.Grants(List.of(), true), target());
        assertNotEquals(0, r.exitCode(), r.output());
        assertFalse(r.output().contains(TestProxy.BODY), r.output());
    }

    @Test
    void theProxyStillAppliesTheRules() {
        // Same proxy, but the target port has no allow rule: the built-in loopback deny blocks it.
        ProcessResult r = curl(new ChildSandbox.Grants(List.of(), true), "-x", net.proxyUrl(), "http://127.0.0.1:9/");
        assertFalse(r.output().contains(TestProxy.BODY), r.output());
        assertTrue(events.stream().anyMatch(e -> e.type().equals("network.blocked")), events.toString());
    }

    @Test
    void withoutTheGrantThereIsNoNetwork() {
        ProcessResult r = curl(ChildSandbox.Grants.NONE, "-x", net.proxyUrl(), target());
        assertNotEquals(0, r.exitCode(), r.output());
        assertFalse(r.output().contains(TestProxy.BODY), r.output());
    }

    @Test
    void theProxyPasswordIsRedactedFromOutput() {
        String pass = net.password();
        ProcessResult r = curl(new ChildSandbox.Grants(List.of(), true), "-x", net.proxyUrl(), "-w", " " + pass, target());
        assertFalse(r.output().contains(pass), r.output());
        assertFalse(r.full().contains(pass), r.full());
        assertTrue(r.output().contains("***"), r.output());
    }
}
