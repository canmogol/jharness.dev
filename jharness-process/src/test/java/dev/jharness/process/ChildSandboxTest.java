package dev.jharness.process;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.events.EventBus;
import dev.jharness.events.Origin;

/** Spec 012: the generated policy, fail-closed behaviour, and the sandbox on this OS. */
class ChildSandboxTest {

    @TempDir
    Path tmp;
    Path ws;
    Path childTmp;
    Path home;
    Path outside;

    @BeforeEach
    void setUp() throws Exception {
        ws = Files.createDirectories(tmp.resolve("ws")).toRealPath();
        childTmp = Files.createDirectories(tmp.resolve("child-tmp")).toRealPath();
        home = Files.createDirectories(tmp.resolve("home")).toRealPath();
        outside = Files.createDirectories(tmp.resolve("outside")).toRealPath();
        Files.createDirectories(home.resolve(".ssh"));
        Files.writeString(home.resolve(".ssh/id_ed25519"), "SECRET-KEY\n");
        Files.writeString(home.resolve(".netrc"), "machine x password SECRET-NETRC\n");
    }

    private ChildSandbox.Policy policy() {
        return ChildSandbox.Policy.forSession(ws, childTmp, home);
    }

    private ChildSandbox sandbox() {
        ChildSandbox s = ChildSandbox.detect(policy());
        assumeTrue(s.available(), "no OS sandbox here: " + s.reason());
        return s;
    }

    /** A test-only script; production code can only resolve git and rg. */
    private Executable script(String body) throws Exception {
        Path f = ws.resolve("script-" + System.nanoTime() + ".sh");
        Files.writeString(f, "#!/bin/bash\n" + body + "\n");
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rwx------"));
        return new Executable("script", f);
    }

    private ProcessResult run(ChildSandbox s, String body) throws Exception {
        return new ProcessRunner(new EventBus("s-sb"), () -> Origin.main(1), 30_000, s).run(script(body), List.of(), ws,
                Duration.ofSeconds(20), Map.of());
    }

    @Test
    void seatbeltProfileDeniesByDefault() {
        String p = ChildSandbox.seatbeltProfile(policy());
        assertTrue(p.startsWith("(version 1)\n(deny default)\n"), p);
        assertTrue(p.contains("(allow file-write* (subpath \"" + ws + "\") (subpath \"" + childTmp + "\")"), p);
        assertTrue(p.contains("(deny file-write* (subpath \"" + ws.resolve(".jharness") + "\"))"), p);
        assertTrue(p.contains("(subpath \"" + home.resolve(".ssh") + "\")") && p.contains("(subpath \"" + home.resolve(".jharness") + "\")"), p);
        assertFalse(p.contains("network"), "no network rule means no network: " + p);
        assertEquals("\"/a \\\"b\\\" \\\\c\"", ChildSandbox.quote(Path.of("/a \"b\" \\c")));
    }

    @Test
    void buildGrantsAddCachesAndOnlyTheProxyPort() {
        Path cache = home.resolve(".npm");
        var route = new ChildSandbox.ProxyRoute(4321, tmp.resolve("proxy.sock"), "u", "p");
        String p = ChildSandbox.seatbeltProfile(policy(), new ChildSandbox.Grants(List.of(cache), true), route);
        assertTrue(p.contains("(subpath \"" + cache + "\")"), p);
        assertTrue(p.contains("(allow network-outbound (remote ip \"localhost:4321\"))"), p);
        assertEquals(1, p.split("network-outbound", -1).length - 1, p);
        assertFalse(p.contains("network-bind") || p.contains("network-inbound"), "no listening: " + p);
        List<String> b = ChildSandbox.bubblewrapArgs(policy(), ws, new ChildSandbox.Grants(List.of(cache), true), route);
        assertTrue(b.contains("--unshare-all"), b.toString());
        assertFalse(b.contains("--share-net"), b.toString());
        assertTrue(String.join(" ", b).contains("--bind " + cache + " " + cache), b.toString());
        assertTrue(String.join(" ", b).contains("--bind " + route.socket() + " " + route.socket()), b.toString());
    }

    @Test
    void gradleAloneGetsLoopback() {
        var route = new ChildSandbox.ProxyRoute(4321, tmp.resolve("proxy.sock"), "u", "p");
        String p = ChildSandbox.seatbeltProfile(policy(), BuildOperations.grants(BuildOperations.Tool.GRADLE, List.of()), route);
        assertTrue(p.contains("(allow network-outbound (remote ip \"localhost:4321\"))"), p);
        assertTrue(p.contains("(allow network-bind (local ip \"localhost:*\"))"), p);
        assertTrue(p.contains("(allow network-inbound (local ip \"localhost:*\"))"), p);
        assertTrue(p.contains("(allow network-outbound (remote ip \"localhost:*\"))"), p);
        assertFalse(p.contains("remote ip \"*:") || p.contains("(remote tcp") || p.contains("(allow network*"), "only loopback: " + p);
        for (var tool : List.of(BuildOperations.Tool.MAVEN, BuildOperations.Tool.NPM)) {
            String q = ChildSandbox.seatbeltProfile(policy(), BuildOperations.grants(tool, List.of()), route);
            assertFalse(q.contains("localhost:*"), tool + ": " + q);
        }
        assertThrows(IllegalArgumentException.class, () -> new ChildSandbox.Grants(List.of(), false, true));
        List<String> b = ChildSandbox.bubblewrapArgs(policy(), ws, BuildOperations.grants(BuildOperations.Tool.GRADLE, List.of()), route);
        assertTrue(b.contains("--unshare-all") && !b.contains("--share-net"), b.toString());
    }

    @Test
    void bubblewrapArguments() {
        List<String> a = ChildSandbox.bubblewrapArgs(policy(), ws);
        String s = String.join(" ", a);
        assertTrue(s.startsWith("/usr/bin/bwrap --die-with-parent --new-session --unshare-all --ro-bind / / --dev /dev --proc /proc"), s);
        assertTrue(s.contains("--bind " + ws + " " + ws + " --ro-bind " + ws.resolve(".jharness") + " " + ws.resolve(".jharness")), s);
        assertTrue(s.contains("--bind " + childTmp + " " + childTmp), s);
        assertTrue(s.contains("--tmpfs " + home.resolve(".ssh")), "hidden folders become empty: " + s);
        assertTrue(s.contains("--ro-bind /dev/null " + home.resolve(".netrc")), "hidden files become empty: " + s);
        assertFalse(s.contains(home.resolve(".aws").toString()), "missing paths are skipped: " + s);
        assertTrue(s.endsWith("--chdir " + ws), s);
        assertTrue(a.contains("--unshare-all"), "includes the network namespace");
    }

    @Test
    void noSandboxNoProcess() throws Exception {
        ChildSandbox none = ChildSandbox.detect(policy(), "Plan 9");
        assertFalse(none.available());
        assertEquals(ChildSandbox.Mechanism.NONE, none.mechanism());
        assertTrue(none.reason().contains("no OS sandbox for Plan 9"), none.reason());
        Path marker = ws.resolve("ran");
        OperationException e = assertThrows(OperationException.class, () -> run(none, "touch " + marker));
        assertTrue(e.getMessage().contains("no OS sandbox"), e.getMessage());
        assertFalse(Files.exists(marker), "nothing ran unsandboxed");
    }

    @Test
    void writesOnlyToTheWorkspaceAndPrivateTemp() throws Exception {
        ChildSandbox s = sandbox();
        Files.createDirectories(ws.resolve(".jharness"));
        ProcessResult r = run(s, String.join("\n",
                "echo ok > in-workspace.txt && echo ws-ok",
                "echo ok > \"$TMPDIR/in-tmp.txt\" && echo tmp-ok",
                "echo bad > " + outside.resolve("x.txt") + " 2>/dev/null || echo outside-denied",
                "echo bad > .jharness/settings.json 2>/dev/null || echo jharness-denied",
                "echo bad > " + home.resolve("planted.txt") + " 2>/dev/null || echo home-denied",
                "[ \"$TMPDIR\" = \"" + childTmp + "\" ] && echo tmpdir-set"));
        String o = r.output();
        for (String want : List.of("ws-ok", "tmp-ok", "outside-denied", "jharness-denied", "home-denied", "tmpdir-set")) {
            assertTrue(o.contains(want), want + " in: " + o);
        }
        assertFalse(Files.exists(outside.resolve("x.txt")));
        assertFalse(Files.exists(ws.resolve(".jharness/settings.json")));
        assertTrue(Files.exists(childTmp.resolve("in-tmp.txt")));
    }

    @Test
    void credentialFoldersAreHidden() throws Exception {
        ChildSandbox s = sandbox();
        Files.writeString(outside.resolve("readable.txt"), "PUBLIC\n");
        ProcessResult r = run(s, String.join("\n",
                "cat " + home.resolve(".ssh/id_ed25519") + " 2>/dev/null || echo ssh-hidden",
                "cat " + home.resolve(".netrc") + " 2>/dev/null || true",
                "cat " + outside.resolve("readable.txt")));
        assertFalse(r.output().contains("SECRET"), r.output());
        assertTrue(r.output().contains("PUBLIC"), "other reads work: " + r.output());
    }

    @Test
    void noNetworkNotEvenLoopback() throws Exception {
        ChildSandbox s = sandbox();
        AtomicBoolean accepted = new AtomicBoolean();
        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
                DatagramSocket udp = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(3000);
            Thread t = Thread.ofVirtual().start(() -> {
                try (var c = server.accept()) {
                    accepted.set(true);
                } catch (IOException e) {
                    // nothing came
                }
            });
            ProcessResult r = run(s, String.join("\n",
                    "(exec 3<>/dev/tcp/127.0.0.1/" + server.getLocalPort() + ") 2>/dev/null && echo tcp-open || echo tcp-denied",
                    "(echo ping > /dev/udp/127.0.0.1/" + udp.getLocalPort() + ") 2>/dev/null; echo udp-tried"));
            t.join(4000);
            assertTrue(r.output().contains("tcp-denied"), r.output());
            assertFalse(accepted.get(), "the server saw a connection");
            udp.setSoTimeout(500);
            DatagramPacket p = new DatagramPacket(new byte[16], 16);
            assertThrows(SocketTimeoutException.class, () -> udp.receive(p), "a UDP packet got out");
        }
    }

    @Test
    void timeoutsStillKillTheTree() throws Exception {
        ChildSandbox s = sandbox();
        Path marker = ws.resolve("late");
        long t0 = System.nanoTime();
        ProcessResult r = new ProcessRunner(new EventBus("s-sb"), () -> Origin.main(1), 30_000, s).run(
                script("(sleep 3; touch " + marker + ") & sleep 30"), List.of(), ws, Duration.ofSeconds(1), Map.of());
        assertTrue(r.timedOut());
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 10_000, "returned soon after the timeout");
        Thread.sleep(3500);
        assertFalse(Files.exists(marker), "a child survived the kill");
    }

    @Test
    void processStartedNamesTheMechanism() throws Exception {
        ChildSandbox s = sandbox();
        EventBus bus = new EventBus("s-ev");
        List<dev.jharness.events.Event> events = new java.util.ArrayList<>();
        bus.subscribe(events::add);
        new ProcessRunner(bus, () -> Origin.main(1), 30_000, s).run(script("true"), List.of(), ws, Duration.ofSeconds(10), Map.of());
        assertEquals(s.mechanism().wire(), events.getFirst().body().get("sandbox").asText());
    }
}
