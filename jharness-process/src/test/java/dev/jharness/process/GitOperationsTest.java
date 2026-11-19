package dev.jharness.process;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.events.Event;
import dev.jharness.events.EventBus;
import dev.jharness.events.Json;
import dev.jharness.events.Origin;
import dev.jharness.security.Operation;
import dev.jharness.security.Workspace;

class GitOperationsTest {

    @TempDir
    Path tmp;
    Path ws;
    GitOperations git;
    final List<Event> events = new ArrayList<>();

    /** Test setup only: git init and identity, outside ProcessRunner. */
    private void sh(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(ws.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), out);
    }

    @BeforeEach
    void setUp() throws Exception {
        ws = Files.createDirectories(tmp.resolve("repo"));
        Executable exe = Executables.resolve("git", ws).orElseThrow();
        sh(exe.path().toString(), "init", "-q", "-b", "main");
        sh(exe.path().toString(), "config", "user.email", "test@example.invalid");
        sh(exe.path().toString(), "config", "user.name", "Test");
        EventBus bus = new EventBus("s-git");
        bus.subscribe(events::add);
        git = new GitOperations(new ProcessRunner(bus, () -> Origin.main(1), 30_000, TestSandboxes.in(ws)), exe, new Workspace(ws), Duration.ofSeconds(30));
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode req(String json) {
        return Json.parseObject(json);
    }

    @Test
    void localWorkflow() throws Exception {
        Files.writeString(ws.resolve("A.java"), "class A {}\n");
        ProcessResult st = git.run(req("{\"op\":\"status\"}"));
        assertTrue(st.output().contains("?? A.java"), st.output());
        assertEquals(0, git.run(req("{\"op\":\"add\",\"paths\":[\"A.java\"]}")).exitCode());
        ProcessResult c = git.run(req("{\"op\":\"commit\",\"message\":\"--amend first\"}"));
        assertEquals(0, c.exitCode(), c.output());
        ProcessResult log = git.run(req("{\"op\":\"log\",\"oneline\":true,\"max\":5}"));
        assertTrue(log.output().contains("--amend first"), log.output());
        Files.writeString(ws.resolve("A.java"), "class A { int x; }\n");
        assertTrue(git.run(req("{\"op\":\"diff\",\"paths\":[\"A.java\"]}")).output().contains("+class A { int x; }"));
        assertTrue(git.run(req("{\"op\":\"show\",\"rev\":\"HEAD\",\"stat\":true}")).output().contains("A.java"));
        assertEquals(0, git.run(req("{\"op\":\"restore\",\"paths\":[\"A.java\"]}")).exitCode());
        assertEquals("class A {}\n", Files.readString(ws.resolve("A.java")));
        assertTrue(git.run(req("{\"op\":\"branch\"}")).output().contains("main"));
    }

    @Test
    void everyCallCarriesTheHardeningPrefix() {
        git.run(req("{\"op\":\"status\"}"));
        var args = events.getFirst().body().get("args");
        List<String> a = new ArrayList<>();
        args.forEach(n -> a.add(n.asText()));
        assertEquals(GitOperations.HARDENING, a.subList(0, GitOperations.HARDENING.size()));
        assertTrue(a.contains("protocol.allow=never"));
    }

    @Test
    void hooksAndFsmonitorDoNotRun() throws Exception {
        Path marker = tmp.resolve("ran");
        Path hook = ws.resolve(".git/hooks/pre-commit");
        Files.writeString(hook, "#!/bin/sh\ntouch " + marker + "\n");
        Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rwx------"));
        Path mon = tmp.resolve("monitor.sh");
        Files.writeString(mon, "#!/bin/sh\ntouch " + marker + "\n");
        Files.setPosixFilePermissions(mon, PosixFilePermissions.fromString("rwx------"));
        sh(Executables.resolve("git", ws).orElseThrow().path().toString(), "config", "core.fsmonitor", mon.toString());
        Files.writeString(ws.resolve("B.txt"), "b");
        git.run(req("{\"op\":\"status\"}"));
        git.run(req("{\"op\":\"add\",\"paths\":[\"B.txt\"]}"));
        assertEquals(0, git.run(req("{\"op\":\"commit\",\"message\":\"b\"}")).exitCode());
        assertFalse(Files.exists(marker), "a hook or fsmonitor ran");
    }

    @Test
    void rejectsUnknownOpsFieldsAndUnsafeArguments() {
        for (String bad : List.of("{\"op\":\"push\"}", "{\"op\":\"fetch\"}", "{\"op\":\"clone\"}", "{}", "{\"op\":\"status\",\"args\":[\"-c\"]}",
                "{\"op\":\"diff\",\"rev\":\"--output=/tmp/x\"}", "{\"op\":\"show\"}", "{\"op\":\"log\",\"rev\":\"-p\"}",
                "{\"op\":\"add\"}", "{\"op\":\"add\",\"paths\":[]}", "{\"op\":\"commit\"}", "{\"op\":\"commit\",\"message\":\"  \"}",
                "{\"op\":\"diff\",\"staged\":\"yes\"}", "{\"op\":\"log\",\"max\":0}")) {
            assertThrows(OperationException.class, () -> git.args(req(bad)), bad);
        }
        assertThrows(Workspace.PathException.class, () -> git.args(req("{\"op\":\"add\",\"paths\":[\"../x\"]}")));
        assertThrows(Workspace.PathException.class, () -> git.args(req("{\"op\":\"add\",\"paths\":[\"/etc/passwd\"]}")));
    }

    @Test
    void pathsComeAfterDoubleDashAndFlagLikePathsAreLiteral() throws Exception {
        Files.writeString(ws.resolve("-p"), "x");
        List<String> a = git.args(req("{\"op\":\"add\",\"paths\":[\"-p\"]}"));
        assertEquals(List.of("add", "--", "-p"), a);
        assertEquals(0, git.run(req("{\"op\":\"add\",\"paths\":[\"-p\"]}")).exitCode());
    }

    @Test
    void kinds() {
        assertEquals(Operation.Kind.GIT_READ, GitOperations.kind(req("{\"op\":\"diff\"}")));
        assertEquals(Operation.Kind.GIT_WRITE, GitOperations.kind(req("{\"op\":\"commit\",\"message\":\"m\"}")));
        assertEquals(Operation.Kind.GIT_WRITE, GitOperations.kind(req("{\"op\":\"restore\",\"paths\":[\"a\"]}")));
    }
}
