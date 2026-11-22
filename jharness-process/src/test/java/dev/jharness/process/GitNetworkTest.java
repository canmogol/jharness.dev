package dev.jharness.process;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.jharness.events.Event;
import dev.jharness.events.EventBus;
import dev.jharness.events.Json;
import dev.jharness.events.Origin;
import dev.jharness.security.Operation;
import dev.jharness.security.Workspace;

/** Spec 017: git fetch, pull, push and clone over HTTPS through the real proxy and OS sandbox. */
class GitNetworkTest {

    @TempDir
    Path tmp;
    Path ws;
    GitServer srv;
    EventBus bus;
    final List<Event> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        ws = Files.createDirectories(tmp.resolve("ws")).toRealPath();
        bus = new EventBus("s-gitnet");
        bus.subscribe(events::add);
        srv = new GitServer(tmp, ws, bus);
        assumeTrue(srv.sandbox.available(), "no OS sandbox here: " + srv.sandbox.reason());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (srv != null) {
            srv.close();
        }
    }

    private GitOperations ops(Path root, boolean offline, Map<String, String> tokens) {
        ProcessRunner runner = new ProcessRunner(bus, () -> Origin.main(1), 30_000, srv.sandbox);
        return new GitOperations(runner, new Executable("git", srv.git), new Workspace(root), Duration.ofSeconds(60), Duration.ofMinutes(10),
                offline, tokens, srv.caConfig());
    }

    private GitOperations ops(Path root) {
        return ops(root, false, Map.of(srv.hostKey(), GitServer.TOKEN));
    }

    private static ObjectNode req(String json) {
        return Json.parseObject(json);
    }

    private ProcessResult ok(GitOperations g, String json) {
        ProcessResult r = g.run(req(json));
        assertEquals(0, r.exitCode(), json + ": " + r.output());
        return r;
    }

    private void identity(Path repo) throws Exception {
        GitServer.sh(repo, srv.git.toString(), "config", "user.name", "Test");
        GitServer.sh(repo, srv.git.toString(), "config", "user.email", "test@example.invalid");
    }

    @Test
    void cloneFetchPullAndPushThroughTheProxy() throws Exception {
        GitOperations top = ops(ws);
        assertEquals("git clone " + srv.url() + " into repo", top.summary(req("{\"op\":\"clone\",\"url\":\"" + srv.url() + "\"}")));
        ok(top, "{\"op\":\"clone\",\"url\":\"" + srv.url() + "\"}");
        ok(top, "{\"op\":\"clone\",\"url\":\"" + srv.url() + "\",\"dir\":\"b\"}");
        assertEquals("seed\n", Files.readString(ws.resolve("repo/README.md")));
        assertTrue(events.stream().anyMatch(e -> e.type().equals("network.allowed") && e.body().toString().contains(GitServer.HOST)),
                "the clone went through the proxy");
        assertTrue(events.stream().anyMatch(e -> e.type().equals("process.started") && e.body().path("network").asBoolean()));

        Path a = ws.resolve("repo");
        Path b = ws.resolve("b");
        identity(b);
        GitOperations gb = ops(b);
        Files.writeString(b.resolve("B.txt"), "from b\n");
        ok(gb, "{\"op\":\"add\",\"paths\":[\"B.txt\"]}");
        ok(gb, "{\"op\":\"commit\",\"message\":\"add B\"}");
        assertEquals("git push origin main → " + srv.url(), gb.summary(req("{\"op\":\"push\",\"ref\":\"main\"}")));
        ok(gb, "{\"op\":\"push\",\"ref\":\"main\"}");
        assertTrue(srv.log().contains("add B"), srv.log());

        GitOperations ga = ops(a);
        ok(ga, "{\"op\":\"fetch\",\"prune\":true}");
        assertTrue(ok(ga, "{\"op\":\"status\"}").output().contains("behind 1"));
        ok(ga, "{\"op\":\"pull\",\"ref\":\"main\"}");
        assertEquals("from b\n", Files.readString(a.resolve("B.txt")));

        String all = events.toString();
        assertFalse(all.contains(GitServer.TOKEN) || all.contains(GitOperations.basic(GitServer.TOKEN)), "token stays out of events");
        assertFalse(all.contains(new String(srv.proxy.credentials().getPassword())), "proxy password stays out of events");
        assertTrue(srv.authorizations.stream().allMatch(h -> h.equals("Basic " + GitOperations.basic(GitServer.TOKEN))));
    }

    @Test
    void tokensGoOnlyToTheirHost() throws Exception {
        GitOperations top = ops(ws, false, Map.of("other.test", GitServer.TOKEN));
        ok(top, "{\"op\":\"clone\",\"url\":\"" + srv.url() + "\"}");
        Path a = ws.resolve("repo");
        identity(a);
        GitOperations ga = ops(a, false, Map.of("other.test", GitServer.TOKEN));
        Files.writeString(a.resolve("C.txt"), "c\n");
        ok(ga, "{\"op\":\"add\",\"paths\":[\"C.txt\"]}");
        ok(ga, "{\"op\":\"commit\",\"message\":\"add C\"}");
        ProcessResult push = ga.run(req("{\"op\":\"push\"}"));
        assertNotEquals(0, push.exitCode(), push.output());
        assertTrue(srv.authorizations.isEmpty(), "no token was sent to " + GitServer.HOST + ": " + srv.authorizations);
        assertFalse(srv.log().contains("add C"));
    }

    @Test
    void deniedHostsFailAtTheProxy() {
        ProcessResult r = ops(ws).run(req("{\"op\":\"clone\",\"url\":\"https://denied.test/x.git\"}"));
        assertNotEquals(0, r.exitCode());
        assertFalse(Files.exists(ws.resolve("x")));
        assertTrue(events.stream().anyMatch(e -> e.type().equals("network.blocked") && e.body().toString().contains("denied.test")),
                events.toString());
    }

    @Test
    void onlyHttpsRemotes() throws Exception {
        ok(ops(ws), "{\"op\":\"clone\",\"url\":\"" + srv.url() + "\"}");
        Path a = ws.resolve("repo");
        GitOperations ga = ops(a);
        for (String url : List.of("http://" + srv.hostKey() + "/repo.git", "git@github.com:a/b.git", "ssh://git@github.com/a/b.git",
                srv.root.resolve("repo.git").toString(), "ext::sh -c touch% /tmp/pwned")) {
            GitServer.sh(a, srv.git.toString(), "remote", "set-url", "origin", url);
            OperationException e = assertThrows(OperationException.class, () -> ga.run(req("{\"op\":\"fetch\"}")), url);
            assertTrue(e.getMessage().contains("https:// remotes only"), e.getMessage());
        }
        GitServer.sh(a, srv.git.toString(), "remote", "set-url", "origin", srv.url());
        GitServer.sh(a, srv.git.toString(), "remote", "set-url", "--push", "origin", "http://" + srv.hostKey() + "/repo.git");
        assertThrows(OperationException.class, () -> ga.summary(req("{\"op\":\"push\"}")), "the push URL is checked too");
        OperationException none = assertThrows(OperationException.class, () -> ga.run(req("{\"op\":\"fetch\",\"remote\":\"upstream\"}")));
        assertTrue(none.getMessage().contains("no remote named \"upstream\""), none.getMessage());
    }

    @Test
    void riskyRepositoryConfigIsRefused() throws Exception {
        ok(ops(ws), "{\"op\":\"clone\",\"url\":\"" + srv.url() + "\"}");
        Path a = ws.resolve("repo");
        Path marker = a.resolve(".git/pwned");
        for (String[] kv : List.of(new String[] {"credential.helper", "!touch " + marker},
                new String[] {"credential.https://" + GitServer.HOST + ".helper", "!touch " + marker},
                new String[] {"core.askPass", marker.toString()}, new String[] {"core.sshCommand", "touch " + marker},
                new String[] {"protocol.file.allow", "always"}, new String[] {"http.cookieFile", "/etc/passwd"},
                new String[] {"remote.origin.uploadpack", "touch " + marker})) {
            GitServer.sh(a, srv.git.toString(), "config", kv[0], kv[1]);
            OperationException e = assertThrows(OperationException.class, () -> ops(a).run(req("{\"op\":\"fetch\"}")), kv[0]);
            assertTrue(e.getMessage().contains(kv[0].toLowerCase().substring(0, kv[0].indexOf('.'))), e.getMessage());
            GitServer.sh(a, srv.git.toString(), "config", "--unset", kv[0]);
        }
        assertFalse(Files.exists(marker));
        ok(ops(a), "{\"op\":\"fetch\"}");
    }

    @Test
    void offlineAndNoProxyRefuseAtOnce() throws Exception {
        GitOperations off = ops(ws, true, Map.of());
        for (String json : List.of("{\"op\":\"clone\",\"url\":\"" + srv.url() + "\"}", "{\"op\":\"fetch\"}", "{\"op\":\"pull\"}",
                "{\"op\":\"push\"}")) {
            OperationException e = assertThrows(OperationException.class, () -> off.run(req(json)), json);
            assertTrue(e.getMessage().startsWith("offline mode: git "), e.getMessage());
        }
        assertThrows(OperationException.class, () -> off.summary(req("{\"op\":\"clone\",\"url\":\"" + srv.url() + "\"}")));

        ProcessRunner noProxy = new ProcessRunner(bus, () -> Origin.main(1), 30_000, TestSandboxes.in(ws));
        GitOperations g = new GitOperations(noProxy, new Executable("git", srv.git), new Workspace(ws), Duration.ofSeconds(30));
        OperationException e = assertThrows(OperationException.class, () -> g.run(req("{\"op\":\"clone\",\"url\":\"" + srv.url() + "\"}")));
        assertTrue(e.getMessage().contains("needs the egress proxy"), e.getMessage());
    }

    @Test
    void requestsAreChecked() throws Exception {
        GitOperations g = ops(ws);
        Files.createDirectories(ws.resolve("taken"));
        Map<String, String> bad = Map.of(
                "{\"op\":\"fetch\",\"ref\":\"+main\"}", "\"ref\"",
                "{\"op\":\"push\",\"ref\":\"main:main\"}", "\"ref\"",
                "{\"op\":\"push\",\"ref\":\"--force\"}", "\"ref\"",
                "{\"op\":\"push\",\"remote\":\"-o\"}", "\"remote\"",
                "{\"op\":\"push\",\"force\":true}", "does not take \"force\"",
                "{\"op\":\"clone\",\"url\":\"http://example.com/a.git\"}", "https://",
                "{\"op\":\"clone\",\"url\":\"https://u:p@example.com/a.git\"}", "user name",
                "{\"op\":\"clone\",\"url\":\"https://example.com/a.git\",\"dir\":\"taken\"}", "already exists",
                "{\"op\":\"clone\",\"url\":\"https://example.com/a.git\",\"dir\":\"../out\"}", "",
                "{\"op\":\"clone\"}", "needs a \"url\"");
        bad.forEach((json, msg) -> {
            RuntimeException e = assertThrows(RuntimeException.class, () -> g.args(req(json)), json);
            assertTrue(e.getMessage().contains(msg), json + ": " + e.getMessage());
        });
        assertEquals(List.of("push", "--no-verify", "--set-upstream", "origin", "feature/x"),
                g.args(req("{\"op\":\"push\",\"ref\":\"feature/x\",\"setUpstream\":true}")));
        assertEquals(List.of("pull", "--ff-only", "--no-rebase", "--no-recurse-submodules", "--no-edit", "origin"), g.args(req("{\"op\":\"pull\"}")));
        assertEquals(List.of("clone", "--no-recurse-submodules", "--", "https://example.com/a/lib.git", "lib"),
                g.args(req("{\"op\":\"clone\",\"url\":\"https://example.com/a/lib.git\"}")));
        assertEquals(Operation.Kind.GIT_READ, GitOperations.kind(req("{\"op\":\"fetch\"}")));
        for (String op : List.of("pull", "push", "clone")) {
            assertEquals(Operation.Kind.GIT_WRITE, GitOperations.kind(req("{\"op\":\"" + op + "\"}")), op);
        }
    }
}
