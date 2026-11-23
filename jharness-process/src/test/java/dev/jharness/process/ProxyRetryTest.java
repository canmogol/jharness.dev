package dev.jharness.process;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.config.Timeouts;
import dev.jharness.events.Event;
import dev.jharness.events.EventBus;
import dev.jharness.events.Json;
import dev.jharness.events.Origin;
import dev.jharness.process.BuildOperations.Tool;
import dev.jharness.security.Workspace;

/** Spec 019: runs that the sandbox's proxy refusal failed are repeated, within the call's timeout. */
class ProxyRetryTest {

    @TempDir
    Path tmp;

    final EventBus bus = new EventBus("s-retry");
    final List<Event> events = new ArrayList<>();

    ProxyRetryTest() {
        bus.subscribe(events::add);
    }

    private List<String> types() {
        return events.stream().map(Event::type).toList();
    }

    private static ProcessResult result(int code, String out, boolean timedOut) {
        return new ProcessResult(code, out, false, out, false, timedOut, 1);
    }

    @Test
    void refusalLinesFromEachTool() {
        assertTrue(ProcessRunner.proxyRefused(
                "npm error request to https://registry.npmjs.org/x failed, reason: connect EPERM 127.0.0.1:4321\n", 4321));
        assertTrue(ProcessRunner.proxyRefused("[ERROR] Could not transfer artifact a:b:pom:1 from/to central"
                + " (https://repo.maven.apache.org/maven2): Connect to 127.0.0.1:4321 [/127.0.0.1] failed: Operation not permitted", 4321));
        assertTrue(ProcessRunner.proxyRefused("> Connect to localhost:4321 failed: operation not permitted", 4321));
        assertTrue(ProcessRunner.proxyRefused(
                "fatal: unable to access 'https://h/r.git/': Failed to connect to 127.0.0.1 port 4321 after 0 ms: Operation not permitted", 4321));
    }

    @Test
    void otherFailuresAreNotRefusals() {
        assertFalse(ProcessRunner.proxyRefused("connect EPERM 127.0.0.1:43210", 4321), "another port");
        assertFalse(ProcessRunner.proxyRefused("connect EPERM 127.0.0.1:5432", 4321), "another port");
        assertFalse(ProcessRunner.proxyRefused("EPERM: operation not permitted, open '/etc/x'", 4321), "a file");
        assertFalse(ProcessRunner.proxyRefused("connect ECONNREFUSED 127.0.0.1:4321", 4321), "another error");
        assertFalse(ProcessRunner.proxyRefused("connect EPERM 10.0.0.1:4321\n127.0.0.1:4321 ok", 4321), "split over lines");
    }

    private ProcessRunner runner() {
        return new ProcessRunner(bus, () -> Origin.main(1), 30_000, TestSandboxes.in(tmp));
    }

    @Test
    void retriesUpToThreeRunsAndEmitsAnEventForEachRetry() {
        List<Duration> given = new ArrayList<>();
        ProcessResult r = runner().retryingProxyRefusals("npm", Duration.ofSeconds(30), x -> true, t -> {
            given.add(t);
            return result(1, "refused", false);
        });
        assertEquals(1, r.exitCode());
        assertEquals(ProcessRunner.PROXY_ATTEMPTS, given.size());
        assertEquals(List.of("process.retried", "process.retried"), types());
        assertEquals(2, events.get(0).body().get("attempt").asInt());
        assertEquals(3, events.get(1).body().get("attempt").asInt());
        assertEquals("proxy-connect-refused", events.get(0).body().get("reason").asText());
        assertEquals(Duration.ofSeconds(30), given.get(0));
        assertTrue(given.get(1).compareTo(Duration.ofSeconds(30)) < 0, "a retry gets only what is left of the timeout");
    }

    @Test
    void stopsAtTheFirstRunThatIsntARefusal() {
        int[] runs = {0};
        ProcessResult r = runner().retryingProxyRefusals("npm", Duration.ofSeconds(30), x -> x.output().equals("refused"), t -> {
            runs[0]++;
            return runs[0] == 1 ? result(1, "refused", false) : result(0, "ok", false);
        });
        assertEquals(0, r.exitCode());
        assertEquals(2, runs[0]);
        assertEquals(List.of("process.retried"), types());
    }

    @Test
    void neverRepeatsASuccessATimeoutOrAnotherFailure() {
        for (ProcessResult first : List.of(result(0, "refused", false), result(-1, "refused", true), result(1, "tests failed", false))) {
            int[] runs = {0};
            runner().retryingProxyRefusals("npm", Duration.ofSeconds(30), x -> x.output().equals("refused"), t -> {
                runs[0]++;
                return first;
            });
            assertEquals(1, runs[0], first.toString());
        }
        assertEquals(List.of(), types());
    }

    @Test
    void noRetryWhenTooLittleTimeIsLeft() {
        int[] runs = {0};
        runner().retryingProxyRefusals("npm", Duration.ofMillis(500), x -> true, t -> {
            runs[0]++;
            return result(1, "refused", false);
        });
        assertEquals(1, runs[0]);
        assertEquals(List.of(), types());
    }

    /** A real run of BuildOperations in the OS sandbox: a stand-in npm is refused twice, then succeeds. */
    @Test
    void aBuildRefusedAtTheProxyRunsAgain() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("ws")).toRealPath();
        try (TestProxy net = new TestProxy(root, bus, tmp)) {
            assumeTrue(net.sandbox.available(), "no OS sandbox here: " + net.sandbox.reason());
            int port = net.sandbox.route().port();
            Path bin = Files.createDirectories(tmp.resolve("bin"));
            Path npm = bin.resolve("npm");
            Files.writeString(npm, "#!/bin/sh\n"
                    + "n=$(cat runs 2>/dev/null || echo 0); n=$((n+1)); echo $n > runs\n"
                    + "if [ $n -lt 3 ]; then echo \"npm error request to https://registry.npmjs.org/x failed, reason: connect EPERM 127.0.0.1:"
                    + port + "\"; exit 1; fi\necho installed\n");
            Files.setPosixFilePermissions(npm, PosixFilePermissions.fromString("rwx------"));
            Files.writeString(root.resolve("package.json"), "{}");
            ProcessRunner runner = new ProcessRunner(bus, () -> Origin.main(1), 30_000, net.sandbox);
            BuildOperations ops = new BuildOperations(runner, new Workspace(root), Timeouts.defaults(), true,
                    Map.of(Tool.NPM, new Executable("npm", npm, bin)), tmp.resolve("home"), null);
            ProcessResult r = ops.run(Tool.NPM, Json.parseObject("{\"op\":\"install\"}"));
            assertEquals(0, r.exitCode(), r.full());
            assertTrue(r.output().contains("installed"));
            assertEquals("3", Files.readString(root.resolve("runs")).strip());
            assertEquals(2, types().stream().filter("process.retried"::equals).count());
            assertEquals(3, types().stream().filter("process.started"::equals).count());
        }
    }

    /** A build that fails for any other reason runs once. */
    @Test
    void aFailingBuildRunsOnce() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("ws")).toRealPath();
        try (TestProxy net = new TestProxy(root, bus, tmp)) {
            assumeTrue(net.sandbox.available(), "no OS sandbox here: " + net.sandbox.reason());
            Path bin = Files.createDirectories(tmp.resolve("bin"));
            Path npm = bin.resolve("npm");
            Files.writeString(npm, "#!/bin/sh\nn=$(cat runs 2>/dev/null || echo 0); echo $((n+1)) > runs\n"
                    + "echo \"EPERM: operation not permitted, open '/etc/x'\"; exit 1\n");
            Files.setPosixFilePermissions(npm, PosixFilePermissions.fromString("rwx------"));
            Files.writeString(root.resolve("package.json"), "{}");
            ProcessRunner runner = new ProcessRunner(bus, () -> Origin.main(1), 30_000, net.sandbox);
            BuildOperations ops = new BuildOperations(runner, new Workspace(root), Timeouts.defaults(), true,
                    Map.of(Tool.NPM, new Executable("npm", npm, bin)), tmp.resolve("home"), null);
            assertEquals(1, ops.run(Tool.NPM, Json.parseObject("{\"op\":\"install\"}")).exitCode());
            assertEquals("1", Files.readString(root.resolve("runs")).strip());
            assertFalse(types().contains("process.retried"));
        }
    }
}
