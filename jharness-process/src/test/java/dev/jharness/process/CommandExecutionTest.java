package dev.jharness.process;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.events.Event;
import dev.jharness.events.EventBus;
import dev.jharness.events.Json;
import dev.jharness.events.Origin;
import dev.jharness.security.Workspace;

/**
 * The command-execution checks in one place (spec 015, AC-08): no shell, pinned executables, the
 * {@code o+w} skip, the environment allowlist, argument limits, git hardening against a hostile repo
 * config, and the process-tree kill. Markers live inside the workspace's {@code .git} folder, which the
 * OS sandbox lets children write, so a check that failed would leave one behind.
 */
class CommandExecutionTest {

    @TempDir
    Path tmp;
    Path ws;
    Path git;
    EventBus bus;
    final List<Event> events = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() throws Exception {
        ws = Files.createDirectories(tmp.resolve("repo"));
        bus = new EventBus("s-exec");
        bus.subscribe(events::add);
        git = Executables.resolve("git", ws).orElseThrow().path();
        sh(git.toString(), "init", "-q", "-b", "main");
        sh(git.toString(), "config", "user.email", "test@example.invalid");
        sh(git.toString(), "config", "user.name", "Test");
    }

    /** Test setup only, outside ProcessRunner. */
    private void sh(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(ws.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), out);
    }

    private ProcessRunner runner() {
        return new ProcessRunner(bus, () -> Origin.main(1), 30_000, TestSandboxes.in(ws));
    }

    private GitOperations gitOps() {
        return new GitOperations(runner(), Executables.resolve("git", ws).orElseThrow(), new Workspace(ws), Duration.ofSeconds(30));
    }

    private Path script(String name, String body) throws Exception {
        Path f = tmp.resolve(name);
        Files.writeString(f, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rwx------"));
        return f;
    }

    private Path marker(String name) {
        return ws.resolve(".git").resolve(name);
    }

    // --- No shell ---

    @Test
    void noShellIsKnownAndMetacharactersStayLiteral() throws Exception {
        for (String sh : List.of("sh", "bash", "zsh", "env", "node", "python3")) {
            assertThrows(IllegalArgumentException.class, () -> Executables.resolve(sh, "/bin:/usr/bin", ws), sh);
        }
        Path m = marker("shell");
        ProcessResult r = runner().run(new Executable("fixture", script("echo-arg", "printf '%s' \"$1\"")),
                List.of("$(touch " + m + "); touch " + m + " | `touch " + m + "`"), ws, Duration.ofSeconds(10), Map.of());
        assertEquals(0, r.exitCode(), r.full());
        assertTrue(r.output().startsWith("$(touch"), r.output());
        assertFalse(Files.exists(m));
    }

    // --- Pinned executables (AC-04) ---

    @Test
    void aReplacedExecutableDoesNotRun() throws Exception {
        Path f = script("tool", "echo one");
        Executable exe = new Executable("fixture", f);
        assertEquals(0, runner().run(exe, List.of(), ws, Duration.ofSeconds(10), Map.of()).exitCode());

        // Same path, different file: replaced by rename, as an upgrade or an attacker would.
        Path other = script("tool.new", "echo two; echo longer than before");
        Files.move(other, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        events.clear();
        OperationException e = assertThrows(OperationException.class,
                () -> runner().run(exe, List.of(), ws, Duration.ofSeconds(10), Map.of()));
        assertTrue(e.getMessage().contains("changed since it was resolved"), e.getMessage());
        assertEquals(List.of("process.refused"), events.stream().map(Event::type).toList());
        assertEquals("fixture", events.getFirst().body().get("executable").asText());
    }

    @Test
    void anExecutableRewrittenInPlaceDoesNotRun() throws Exception {
        Path f = script("tool2", "echo one");
        Executable exe = new Executable("fixture", f);
        Files.writeString(f, "#!/bin/sh\necho rewritten, and longer\n");
        assertThrows(OperationException.class, () -> runner().run(exe, List.of(), ws, Duration.ofSeconds(10), Map.of()));
    }

    // --- o+w skip (AC-05) ---

    @Test
    void worldWritableToolsAreSkipped() throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path tool = bin.resolve("git");
        Files.writeString(tool, "#!/bin/sh\n");
        Files.setPosixFilePermissions(tool, PosixFilePermissions.fromString("rwxr-xrwx"));
        assertTrue(Executables.resolve("git", bin.toString(), ws).isEmpty(), "o+w file");

        Files.setPosixFilePermissions(tool, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertEquals(tool.toRealPath(), Executables.resolve("git", bin.toString(), ws).orElseThrow().path());

        Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwxrwxrwx"));
        assertTrue(Executables.resolve("git", bin.toString(), ws).isEmpty(), "o+w folder");
        Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    // --- Clean environment (AC-06) ---

    @Test
    void onlyNamedVariablesReachTheChild() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> ProcessRunner.checkEnv(Map.of("LD_PRELOAD", "/x.so")));
        assertThrows(IllegalArgumentException.class, () -> ProcessRunner.checkEnv(Map.of("DYLD_INSERT_LIBRARIES", "/x")));
        assertThrows(IllegalArgumentException.class, () -> ProcessRunner.checkEnv(Map.of("NODE_OPTIONS", "--require x")));
        assertThrows(IllegalArgumentException.class, () -> ProcessRunner.checkEnv(Map.of("GIT_CONFIG_PARAMETERS", "'a.b=c'")));
        assertThrows(IllegalArgumentException.class, () -> ProcessRunner.checkEnv(Map.of("BASH_ENV", "/x")));
        Path never = marker("never");
        assertThrows(IllegalArgumentException.class, () -> runner().run(new Executable("fixture", script("t", "touch " + never)),
                List.of(), ws, Duration.ofSeconds(10), Map.of("LD_PRELOAD", "/x.so")));
        assertFalse(Files.exists(never));

        ProcessResult r = runner().run(new Executable("fixture", script("env", "/usr/bin/env")), List.of(), ws,
                Duration.ofSeconds(10), Map.of("GIT_TERMINAL_PROMPT", "0"));
        Set<String> allowed = Set.of("PATH", "HOME", "LANG", "LC_ALL", "TMPDIR", "GIT_TERMINAL_PROMPT", "PWD", "SHLVL", "_", "OLDPWD");
        for (String line : r.output().lines().toList()) {
            assertTrue(allowed.contains(line.split("=", 2)[0]), line);
        }
        String tmpdir = r.output().lines().filter(l -> l.startsWith("TMPDIR=")).findFirst().orElseThrow();
        assertNotEquals("TMPDIR=" + System.getenv("TMPDIR"), tmpdir, "the sandbox's temp folder, not ours");
    }

    @Test
    void buildToolEnvironmentsUseOnlyNamedVariables() {
        // What BuildOperations sets for npm (spec 013) must pass the allowlist.
        for (String n : List.of("npm_config_proxy", "npm_config_https_proxy", "npm_config_update_notifier", "npm_config_fund",
                "npm_config_audit", "npm_config_color", "npm_config_progress", "JAVA_HOME", "JAVA_TOOL_OPTIONS", "PATH")) {
            assertDoesNotThrow(() -> ProcessRunner.checkEnv(Map.of(n, "x")), n);
        }
    }

    // --- Argument limits (AC-07) ---

    @Test
    void argumentLimits() {
        assertThrows(IllegalArgumentException.class, () -> ProcessRunner.checkArgs(List.of("a\0b")));
        assertThrows(IllegalArgumentException.class,
                () -> ProcessRunner.checkArgs(Collections.nCopies(ProcessRunner.MAX_ARGS + 1, "x")));
        assertDoesNotThrow(() -> ProcessRunner.checkArgs(Collections.nCopies(ProcessRunner.MAX_ARGS, "x")));
        assertThrows(IllegalArgumentException.class,
                () -> ProcessRunner.checkArgs(List.of("é".repeat(ProcessRunner.MAX_ARG_BYTES / 2 + 1))));
        assertDoesNotThrow(() -> ProcessRunner.checkArgs(List.of("x".repeat(ProcessRunner.MAX_ARG_BYTES))));
        assertThrows(IllegalArgumentException.class,
                () -> ProcessRunner.checkArgs(Collections.nCopies(9, "x".repeat(ProcessRunner.MAX_ARG_BYTES))));
    }

    // --- Git hardening against a hostile repo config (AC-01 to AC-03) ---

    @Test
    void repoFilterDriversDoNotRun() throws Exception {
        Path m = marker("filter");
        Path evil = script("evil-filter", "touch " + m + "; cat");
        Files.writeString(ws.resolve(".gitattributes"), "*.txt filter=evil\n*.md filter=dotted.name\n");
        sh(git.toString(), "config", "filter.evil.clean", evil.toString());
        sh(git.toString(), "config", "filter.evil.smudge", evil.toString());
        sh(git.toString(), "config", "filter.dotted.name.process", evil.toString());
        Files.writeString(ws.resolve("a.txt"), "a\n");
        Files.writeString(ws.resolve("b.md"), "b\n");
        GitOperations ops = gitOps();
        assertEquals(0, ops.run(Json.parseObject("{\"op\":\"add\",\"paths\":[\"a.txt\",\"b.md\",\".gitattributes\"]}")).exitCode());
        assertEquals(0, ops.run(Json.parseObject("{\"op\":\"commit\",\"message\":\"c\"}")).exitCode());
        Files.writeString(ws.resolve("a.txt"), "changed\n");
        ops.run(Json.parseObject("{\"op\":\"status\"}"));
        ops.run(Json.parseObject("{\"op\":\"diff\"}"));
        assertEquals(0, ops.run(Json.parseObject("{\"op\":\"restore\",\"paths\":[\"a.txt\"]}")).exitCode());
        assertEquals("a\n", Files.readString(ws.resolve("a.txt")));
        assertFalse(Files.exists(m), "a repo-defined filter driver ran");
    }

    @Test
    void repoFiltersAreFoundByScope() {
        ProcessResult r = new ProcessResult(0, "", false,
                "local\tfilter.evil.clean\nworktree\tfilter.a.b.smudge\nglobal\tfilter.lfs.clean\ncommand\tfilter.x.clean\n", false, false, 1);
        assertEquals(Set.of("evil", "a.b"), GitOperations.repoFilters(r));
        assertEquals(Set.of(), GitOperations.repoFilters(new ProcessResult(1, "", false, "", false, false, 1)));
        assertEquals(List.of("-c", "filter.evil.clean=", "-c", "filter.evil.smudge=", "-c", "filter.evil.process=",
                "-c", "filter.evil.required=false"), GitOperations.filterOverrides(Set.of("evil")));
    }

    @Test
    void signingAndSignatureChecksDoNotRunTheRepoGpgProgram() throws Exception {
        Path m = marker("gpg");
        Path fake = script("fake-gpg", "touch " + m + "; exit 1");
        sh(git.toString(), "config", "commit.gpgSign", "true");
        sh(git.toString(), "config", "log.showSignature", "true");
        sh(git.toString(), "config", "gpg.program", fake.toString());
        Files.writeString(ws.resolve("a.txt"), "a\n");
        GitOperations ops = gitOps();
        ops.run(Json.parseObject("{\"op\":\"add\",\"paths\":[\"a.txt\"]}"));
        ProcessResult c = ops.run(Json.parseObject("{\"op\":\"commit\",\"message\":\"unsigned\"}"));
        assertEquals(0, c.exitCode(), c.full());
        // A commit object carrying a signature, so log and show would try to verify it.
        String tree = out(git.toString(), "rev-parse", "HEAD^{tree}");
        String obj = "tree " + tree + "\nauthor T <t@t> 1 +0000\ncommitter T <t@t> 1 +0000\n"
                + "gpgsig -----BEGIN PGP SIGNATURE-----\n x\n -----END PGP SIGNATURE-----\n\nsigned\n";
        Path objFile = tmp.resolve("commit-obj");
        Files.writeString(objFile, obj);
        String sha = out(git.toString(), "hash-object", "-t", "commit", "-w", objFile.toString());
        sh(git.toString(), "update-ref", "HEAD", sha);
        assertEquals(0, ops.run(Json.parseObject("{\"op\":\"log\",\"max\":1}")).exitCode());
        assertEquals(0, ops.run(Json.parseObject("{\"op\":\"show\",\"rev\":\"HEAD\"}")).exitCode());
        assertFalse(Files.exists(m), "the repo's gpg.program ran");
    }

    @Test
    void coreWorktreeCannotPointOutsideTheWorkspace() throws Exception {
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "s\n");
        Files.writeString(ws.resolve("in.txt"), "i\n");
        sh(git.toString(), "config", "core.worktree", outside.toString());
        ProcessResult st = gitOps().run(Json.parseObject("{\"op\":\"status\"}"));
        assertTrue(st.output().contains("in.txt"), st.output());
        assertFalse(st.output().contains("secret.txt"), st.output());
        var started = events.stream().filter(e -> e.type().equals("process.started")).toList();
        assertEquals(2, started.size(), "the filter lookup, then the operation");
    }

    @Test
    void hardeningPrefixHasTheNewFlags() {
        for (String f : List.of("commit.gpgSign=false", "tag.gpgSign=false", "log.showSignature=false",
                "submodule.recurse=false", "gc.auto=0", "maintenance.auto=false", "core.hooksPath=/dev/null")) {
            assertTrue(GitOperations.HARDENING.contains(f), f);
        }
        assertEquals(GitOperations.HARDENING, GitOperations.FILTER_QUERY.subList(0, GitOperations.HARDENING.size()));
    }

    // --- Process-tree kill ---

    @Test
    void timeoutKillsGrandchildren() throws Exception {
        Path m = marker("tree");
        ProcessResult r = runner().run(new Executable("fixture", script("tree", "(sh -c 'sleep 2; touch " + m + "') & wait")),
                List.of(), ws, Duration.ofMillis(500), Map.of());
        assertTrue(r.timedOut());
        Thread.sleep(2500);
        assertFalse(Files.exists(m), "a grandchild outlived the timeout");
    }

    private String out(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(ws.toFile()).start();
        String o = new String(p.getInputStream().readAllBytes()).strip();
        assertEquals(0, p.waitFor());
        return o;
    }
}
