package dev.jharness.process;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.events.Event;
import dev.jharness.events.EventBus;
import dev.jharness.events.Origin;

class ProcessRunnerTest {

    @TempDir
    Path tmp;

    final EventBus bus = new EventBus("s-proc");
    final List<Event> events = new ArrayList<>();

    ProcessRunnerTest() {
        bus.subscribe(events::add);
    }

    /** Test-only fixture tool; production code can only resolve git and rg. */
    private Executable fixture(String body) throws Exception {
        Path f = tmp.resolve("fixture-" + System.nanoTime());
        Files.writeString(f, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rwx------"));
        return new Executable("fixture", f);
    }

    private ProcessRunner runner(int chars) {
        return new ProcessRunner(bus, () -> Origin.main(1), chars, TestSandboxes.in(tmp));
    }

    @Test
    void returnsExitCodeAndOutputAndEmitsEvents() throws Exception {
        ProcessResult r = runner(30_000).run(fixture("echo hello; echo err >&2; exit 3"), List.of(), tmp, Duration.ofSeconds(10), Map.of());
        assertEquals(3, r.exitCode());
        assertTrue(r.output().contains("hello") && r.output().contains("err"));
        assertFalse(r.timedOut());
        assertEquals(List.of("process.started", "process.completed"), events.stream().map(Event::type).toList());
        assertEquals(3, events.get(1).body().get("exitCode").asInt());
    }

    @Test
    void argumentsAreNotInterpretedByAShell() throws Exception {
        Path marker = tmp.resolve("pwned");
        ProcessResult r = runner(30_000).run(fixture("printf '%s\\n' \"$1\""),
                List.of("; touch " + marker + " $(touch " + marker + ")"), tmp, Duration.ofSeconds(10), Map.of());
        assertEquals(0, r.exitCode());
        assertTrue(r.output().contains("; touch"));
        assertFalse(Files.exists(marker));
        assertThrows(IllegalArgumentException.class,
                () -> runner(100).run(fixture("true"), List.of("a\0b"), tmp, Duration.ofSeconds(5), Map.of()));
    }

    @Test
    void environmentIsClean() throws Exception {
        ProcessResult r = runner(30_000).run(fixture("env"), List.of(), tmp, Duration.ofSeconds(10), Map.of("GIT_TERMINAL_PROMPT", "0"));
        List<String> names = r.output().lines().map(l -> l.split("=", 2)[0]).toList();
        for (String n : names) {
            assertTrue(List.of("PATH", "HOME", "LANG", "LC_ALL", "TMPDIR", "GIT_TERMINAL_PROMPT", "PWD", "SHLVL", "_", "OLDPWD").contains(n), n);
        }
        assertTrue(r.output().contains("GIT_TERMINAL_PROMPT=0"));
    }

    @Test
    void outputIsCappedToTheTail() throws Exception {
        ProcessResult r = runner(1000).run(fixture("i=0; while [ $i -lt 400 ]; do echo line-$i; i=$((i+1)); done"),
                List.of(), tmp, Duration.ofSeconds(20), Map.of());
        assertEquals(1000, r.output().length());
        assertTrue(r.truncated());
        assertTrue(r.output().endsWith("line-399\n"));
        assertTrue(r.full().startsWith("line-0\n"));
        assertFalse(r.fullTruncated());
    }

    @Test
    void timeoutKillsTheWholeTree() throws Exception {
        // A marker, not the child's PID: inside bubblewrap's PID namespace $! is not a host PID.
        Path marker = tmp.resolve("child-survived");
        ProcessResult r = runner(30_000).run(fixture("(sleep 2; touch " + marker + ") & wait"), List.of(), tmp, Duration.ofSeconds(1), Map.of());
        assertTrue(r.timedOut());
        assertEquals(-1, r.exitCode());
        Thread.sleep(2500);
        assertFalse(Files.exists(marker), "a child outlived the kill");
        assertTrue(events.getLast().body().get("timedOut").asBoolean());
    }

    @Test
    void stdinIsEmpty() throws Exception {
        ProcessResult r = runner(1000).run(fixture("cat; echo done"), List.of(), tmp, Duration.ofSeconds(5), Map.of());
        assertFalse(r.timedOut());
        assertEquals("done\n", r.output());
    }
}
