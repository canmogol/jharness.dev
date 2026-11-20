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

    /** Maven and Gradle are JVMs: with the build tools' {@code JAVA_TOOL_OPTIONS}, a JVM reaches the proxy too. */
    @Test
    void aJvmReachesTheProxyWithTheBuildToolOptions() throws Exception {
        Files.writeString(ws.resolve("Get.java"), """
                import java.net.*;
                public class Get {
                    public static void main(String[] a) throws Exception {
                        Authenticator.setDefault(new Authenticator() {
                            protected PasswordAuthentication getPasswordAuthentication() {
                                return new PasswordAuthentication(System.getProperty("http.proxyUser"),
                                        System.getProperty("http.proxyPassword").toCharArray());
                            }
                        });
                        var c = (HttpURLConnection) URI.create(a[0]).toURL().openConnection();
                        System.out.print(new String(c.getInputStream().readAllBytes()));
                    }
                }
                """);
        Path java = Path.of(System.getProperty("java.home"), "bin", "java").toRealPath();
        // The stub is on 127.0.0.1, which the JDK skips the proxy for by default; real hosts aren't.
        var env = Map.of("JAVA_TOOL_OPTIONS", BuildOperations.javaToolOptions(net.sandbox.route(), net.sandbox.policy().tmp()));
        ProcessResult r = new ProcessRunner(bus, () -> Origin.main(1), 30_000, net.sandbox).run(new Executable("java", java),
                List.of("-XX:-UsePerfData", "-Dhttp.nonProxyHosts=", "Get.java", target()), ws, Duration.ofSeconds(60), env, new ChildSandbox.Grants(List.of(), true));
        assertEquals(0, r.exitCode(), r.full());
        assertTrue(r.output().endsWith(TestProxy.BODY), r.full());
    }

    @Test
    void directConnectionsFailEvenWithNetwork() {
        ProcessResult r = curl(new ChildSandbox.Grants(List.of(), true), target());
        assertNotEquals(0, r.exitCode(), r.output());
        assertFalse(r.output().contains(TestProxy.BODY), r.output());
    }

    @Test
    void loopbackGrantReachesLocalServicesOnlyOnMacOs() {
        // D-050: Gradle's loopback grant opens every local port on macOS (the recorded Article V
        // exception); on Linux the sandbox's loopback is its own, so host services stay out of reach.
        ProcessResult r = curl(new ChildSandbox.Grants(List.of(), true, true), target());
        if (net.sandbox.mechanism() == ChildSandbox.Mechanism.SEATBELT) {
            assertEquals(0, r.exitCode(), r.output());
            assertEquals(TestProxy.BODY, r.output());
        } else {
            assertNotEquals(0, r.exitCode(), r.output());
            assertFalse(r.output().contains(TestProxy.BODY), r.output());
        }
        assertTrue(events.stream().anyMatch(e -> e.type().equals("process.started") && e.body().path("loopback").asBoolean()));
    }

    /**
     * D-050: on macOS the loopback grant can't stop a listener on 0.0.0.0, and Seatbelt doesn't check
     * accepted connections, so a Gradle build there can accept connections from the LAN. Pinned here
     * so a change in either direction is noticed. On Linux the listener is in the sandbox's own
     * network namespace and nothing outside reaches it.
     */
    @Test
    void loopbackGrantListenerIsReachableFromTheLanOnlyOnMacOs() throws Exception {
        java.net.InetAddress lan = java.net.NetworkInterface.networkInterfaces()
                .filter(i -> { try { return i.isUp() && !i.isLoopback(); } catch (java.net.SocketException e) { return false; } })
                .flatMap(java.net.NetworkInterface::inetAddresses)
                .filter(a -> a instanceof java.net.Inet4Address && !a.isLinkLocalAddress())
                .findFirst().orElse(null);
        assumeTrue(lan != null, "no LAN address here");
        int port;
        try (var probe = new java.net.ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        Files.writeString(ws.resolve("Listen.java"), """
                import java.net.*;
                public class Listen {
                    public static void main(String[] a) throws Exception {
                        try (ServerSocket s = new ServerSocket()) {
                            s.bind(new InetSocketAddress("0.0.0.0", Integer.parseInt(a[0])));
                            s.setSoTimeout(8000);
                            System.out.println("listening");
                            try (Socket c = s.accept()) {
                                System.out.println("accepted " + c.getInetAddress().getHostAddress());
                            }
                        }
                    }
                }
                """);
        Path jvm = Path.of(System.getProperty("java.home"), "bin", "java").toRealPath();
        var result = new java.util.concurrent.CompletableFuture<ProcessResult>();
        Thread.ofVirtual().start(() -> result.complete(new ProcessRunner(bus, () -> Origin.main(1), 30_000, net.sandbox)
                .run(new Executable("java", jvm), List.of("-XX:-UsePerfData", "-Djava.net.preferIPv4Stack=true", "Listen.java",
                        Integer.toString(port)), ws, Duration.ofSeconds(30), Map.of(), new ChildSandbox.Grants(List.of(), true, true))));
        boolean connected = false;
        long end = System.nanoTime() + Duration.ofSeconds(6).toNanos();
        while (!connected && System.nanoTime() < end && !result.isDone()) {
            try (var c = new java.net.Socket()) {
                c.connect(new java.net.InetSocketAddress(lan, port), 500);
                connected = true;
            } catch (java.io.IOException e) {
                Thread.sleep(200);
            }
        }
        ProcessResult r = result.get(40, java.util.concurrent.TimeUnit.SECONDS);
        if (net.sandbox.mechanism() == ChildSandbox.Mechanism.SEATBELT) {
            assertTrue(connected, r.full());
            assertTrue(r.output().contains("accepted " + lan.getHostAddress()), r.full());
        } else {
            assertFalse(connected, r.full());
            assertFalse(r.output().contains("accepted"), r.full());
        }
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
