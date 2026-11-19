package dev.jharness.testkit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import dev.jharness.config.Settings;
import dev.jharness.config.Timeouts;
import dev.jharness.events.Event;
import dev.jharness.events.EventBus;
import dev.jharness.events.Json;
import dev.jharness.events.Origin;
import dev.jharness.events.SessionLog;
import dev.jharness.llm.ChatMessage;
import dev.jharness.llm.ChatRequest;
import dev.jharness.llm.ModelProviders;
import dev.jharness.process.Executable;
import dev.jharness.process.Executables;
import dev.jharness.process.GitOperations;
import dev.jharness.process.JavaSearch;
import dev.jharness.process.ProcessRunner;
import dev.jharness.process.Search;
import dev.jharness.sandbox.AgentIsolate;
import dev.jharness.sandbox.ApiServices;
import dev.jharness.sandbox.IsolateManager;
import dev.jharness.sandbox.RunResult;
import dev.jharness.security.PermissionGate;
import dev.jharness.security.Workspace;

/**
 * Native integration test (AC-14): exercises events and the session log, an isolate run calling
 * every {@code api} method, limits, git, {@code rg} and the Java fallback, and both providers
 * against the stub server. Prints one JSON line per check and exits 1 if any fails.
 */
public final class Smoke {

    private final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Smoke s = new Smoke();
        s.run();
        System.out.println(Json.write(Json.object().put("smoke", s.failures.isEmpty() ? "pass" : "fail")
                .put("failures", s.failures.size())));
        System.exit(s.failures.isEmpty() ? 0 : 1);
    }

    private void check(String name, boolean ok, Object detail) {
        System.out.println(Json.write(Json.object().put("check", name).put("status", ok ? "pass" : "fail")
                .put("detail", String.valueOf(detail))));
        if (!ok) {
            failures.add(name);
        }
    }

    private void run() throws Exception {
        Path tmp = Files.createTempDirectory("jharness-smoke");
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path root = Files.createDirectories(tmp.resolve("ws"));
        Origin o = Origin.main(1);

        EventBus bus = new EventBus(EventBus.newSessionId());
        List<Event> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        bus.subscribe(events::add);
        SessionLog log = SessionLog.open(home, root, bus.session());
        bus.subscribe(log);
        bus.publish(o, "session.started", Json.object().put("workspace", root.toString()));

        Settings settings = Settings.parse("{\"sandbox\":{\"main\":{\"sandbox.MaxCPUTime\":\"2s\"}}}", "smoke");
        check("settings", settings.sandbox().profile("main").values().get("sandbox.MaxCPUTime").equals("2s"), "MaxCPUTime=2s");

        // Workspace with a git repository (setup only, outside the product's ProcessRunner).
        Executable gitExe = Executables.resolve("git", root).orElseThrow(() -> new IllegalStateException("git not found"));
        for (List<String> c : List.of(List.of("init", "-q", "-b", "main"), List.of("config", "user.email", "smoke@example.invalid"),
                List.of("config", "user.name", "Smoke"))) {
            List<String> cmd = new ArrayList<>(List.of(gitExe.path().toString()));
            cmd.addAll(c);
            if (new ProcessBuilder(cmd).directory(root.toFile()).start().waitFor() != 0) {
                throw new IllegalStateException("git " + c + " failed");
            }
        }
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/App.java"), "class App {\n  // TODO fix\n  int answer() { return 41; }\n}\n");
        Workspace ws = new Workspace(root);
        dev.jharness.process.ChildSandbox childSandbox = dev.jharness.process.ChildSandbox.detect(dev.jharness.process.ChildSandbox.Policy
                .forSession(root, Files.createDirectories(tmp.resolve("child-tmp")), tmp.resolve("sandbox-home")));
        check("os sandbox", childSandbox.available(), childSandbox.mechanism().wire() + " " + childSandbox.reason());
        ProcessRunner runner = new ProcessRunner(bus, () -> o, Timeouts.defaults().outputChars(), childSandbox);
        GitOperations git = new GitOperations(runner, gitExe, ws, Duration.ofSeconds(60));
        Search search = Search.create(ws, runner, Timeouts.defaults());
        check("search engine", true, search.engine());
        check("java search", new JavaSearch(ws).grep("TODO", ws.root(), null, false, 10).get("matches").size() == 1, "1 match");

        IsolateManager isolates = new IsolateManager(settings.sandbox(), bus, () -> o);
        ApiServices api = new ApiServices(dev.jharness.security.FilePolicy.workspaceOnly(ws), PermissionGate.allowAll(), bus, () -> o, search, git);
        try (AgentIsolate iso = isolates.create("main", "main")) {
            RunResult r = iso.run("""
                    const ctx = JSON.parse(api.readContext());
                    const text = api.readFile('src/App.java');
                    api.editFile('src/App.java', 'return 41', 'return 42');
                    api.writeFile('notes.txt', 'fixed');
                    const files = JSON.parse(api.listFiles({glob: '*.java'})).files;
                    const hits = JSON.parse(api.grep({pattern: 'TODO'})).matches;
                    api.git({op: 'add', paths: ['src/App.java', 'notes.txt']});
                    const commit = JSON.parse(api.git({op: 'commit', message: 'Fix answer'}));
                    const log = JSON.parse(api.git({op: 'log', oneline: true})).output;
                    api.emit({type: 'agent.smoke', ok: true});
                    ({branch: ctx.git.branch, read: text.includes('41'), files, hits: hits.length, commit: commit.exitCode, log});
                    """, api);
            JsonNode v = r.ok() ? Json.parse(r.value()) : Json.object();
            check("api", r.ok() && v.path("read").asBoolean() && v.path("files").toString().equals("[\"src/App.java\"]")
                    && v.path("hits").asInt() == 1 && v.path("commit").asInt() == 0 && v.path("log").asText().contains("Fix answer"),
                    r.ok() ? v : r.forModel());
            check("edit applied", Files.readString(root.resolve("src/App.java")).contains("return 42"), "return 42");
            RunResult err = iso.run("const a = 1;\napi.readFile('../outside');\n", api);
            check("confinement and line numbers", err.status().equals("error") && err.line() == 2 && err.error().contains("outside"),
                    err.forModel());
            RunResult cpu = iso.run("while (true) {}", api);
            check("cpu limit", cpu.status().equals("limit") && "sandbox.MaxCPUTime".equals(cpu.limit()), cpu);
            check("after limit", iso.run("1 + 1", api).value().equals("2"), "fresh context");
        }
        try (AgentIsolate iso = new IsolateManager(
                Settings.parse("{\"sandbox\":{\"main\":{\"wallClock\":\"1s\"}}}", "smoke").sandbox(), bus, () -> o).create("main", "main")) {
            RunResult r = iso.run("while (true) {}", api);
            check("wall clock", r.status().equals("cancelled") && iso.run("3", api).value().equals("3"), r.status());
        }

        try (StubModelServer stub = new StubModelServer()) {
            stub.enqueue(StubModelServer.Reply.sse(Streams.OPENAI_OK)).enqueue(StubModelServer.Reply.sse(Streams.ANTHROPIC_OK));
            Settings ms = Settings.parse("{\"models\":{\"o\":{\"provider\":\"openai-compatible\",\"baseUrl\":\"" + stub.baseUrl()
                    + "/v1\",\"model\":\"m\"},\"c\":{\"provider\":\"anthropic\",\"baseUrl\":\"" + stub.baseUrl()
                    + "\",\"model\":\"m\",\"apiKeyEnv\":\"K\"}}}", "smoke");
            ChatRequest req = new ChatRequest("sys", List.of(ChatMessage.user("hi")), 0);
            String a = ModelProviders.create(ms.models().resolve("o"), bus, () -> o, k -> null).stream(req, t -> {}).text();
            check("openai-compatible", a.contains("api.readFile"), a);
            String b = ModelProviders.create(ms.models().resolve("c"), bus, () -> o, Map.of("K", "key")::get).stream(req, t -> {}).text();
            check("anthropic", b.equals("Hello, world"), b);
        }

        // The egress proxy (spec 011): a model turn through it, and the JDK client's credentials on CONNECT.
        try (StubModelServer stub = new StubModelServer();
                java.net.ServerSocket tls = new java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())) {
            stub.enqueue(StubModelServer.Reply.sse("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"proxied\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"));
            Thread.ofVirtual().start(() -> {
                try (java.net.Socket c = tls.accept()) {
                    c.getInputStream().read();
                } catch (java.io.IOException e) {
                    // closed
                }
            });
            Path proxyHome = tmp.resolve("proxy-home");
            Files.createDirectories(proxyHome.resolve(".jharness"));
            Files.writeString(dev.jharness.agent.Session.generalNetworkRules(proxyHome), "allow 127.0.0.1:" + tls.getLocalPort() + "\n");
            Settings ps = Settings.parse("{\"model\":\"p\",\"models\":{\"p\":{\"provider\":\"openai-compatible\",\"baseUrl\":\"" + stub.baseUrl()
                    + "/v1\",\"model\":\"m\"}}}", "smoke");
            List<Event> net = new java.util.concurrent.CopyOnWriteArrayList<>();
            try (dev.jharness.agent.Session session = dev.jharness.agent.Session.open(proxyHome, Files.createDirectories(tmp.resolve("proxy-ws")), ps,
                    null, dev.jharness.agent.Session.Access.prompter(dev.jharness.security.Prompter.none()))) {
                session.bus().subscribe(e -> {
                    if (e.type().startsWith("network.")) {
                        net.add(e);
                    }
                });
                var t = session.loop().turn("hi", d -> {});
                check("proxy model turn", t.reply().equals("proxied") && net.stream().anyMatch(e -> e.type().equals("network.allowed")), t + " " + net);
                try {
                    dev.jharness.llm.http.HttpClients.create().send(java.net.http.HttpRequest.newBuilder(
                            java.net.URI.create("https://127.0.0.1:" + tls.getLocalPort() + "/")).timeout(Duration.ofSeconds(5)).build(),
                            java.net.http.HttpResponse.BodyHandlers.discarding());
                } catch (java.io.IOException e) {
                    // the TLS handshake fails after the tunnel opens
                }
                check("proxy CONNECT credentials", net.stream().anyMatch(e -> e.type().equals("network.allowed")
                        && e.body().get("port").asInt() == tls.getLocalPort()), net);
            }
        }

        // The agent loop with recorded replies: the P1 gate scenario.
        Path repo = SampleRepo.create(tmp.resolve("repo"));
        try (dev.jharness.agent.Session session = dev.jharness.agent.Session.open(home, repo, Settings.defaults(),
                dev.jharness.agent.Session.Access.gate(PermissionGate.allowAll()), (b, turns) -> dev.jharness.llm.ReplayProvider.ofReplies(SampleRepo.gateReplies(), b, turns))) {
            var t = session.loop().turn(SampleRepo.TASK, d -> {});
            check("agent loop", t.stopReason().equals("done") && t.steps() == 4 && SampleRepo.fixed(repo)
                    && SampleRepo.log(repo).getFirst().equals("Fix Calculator.add to add"), t);
        }

        // Permissions: ask mode with a user who denies, then one who approves for the project.
        Path permRepo = Files.createDirectories(tmp.resolve("perm"));
        Files.writeString(permRepo.resolve("a.txt"), "old");
        List<String> code = List.of("```js\napi.writeFile('a.txt', 'new')\n```", "ok");
        try (dev.jharness.agent.Session session = dev.jharness.agent.Session.open(home, permRepo, Settings.defaults(),
                dev.jharness.agent.Session.Access.prompter(op -> dev.jharness.security.Prompter.Answer.DENY),
                (b, turns) -> dev.jharness.llm.ReplayProvider.ofReplies(code, b, turns))) {
            session.loop().turn("write", d -> {});
            check("permission denied", Files.readString(permRepo.resolve("a.txt")).equals("old"), "a.txt unchanged");
        }
        try (dev.jharness.agent.Session session = dev.jharness.agent.Session.open(home, permRepo, Settings.defaults(),
                dev.jharness.agent.Session.Access.prompter(op -> dev.jharness.security.Prompter.Answer.ALWAYS),
                (b, turns) -> dev.jharness.llm.ReplayProvider.ofReplies(code, b, turns))) {
            session.loop().turn("write", d -> {});
        }
        Path rules = dev.jharness.security.ProjectRules.fileFor(home, permRepo);
        check("saved rule", Files.readString(permRepo.resolve("a.txt")).equals("new") && Files.readString(rules).contains("a.txt"),
                rules);

        log.close();
        List<JsonNode> back = SessionLog.read(log.path());
        List<Event> logged = events.stream().filter(e -> !SessionLog.LIVE_ONLY.contains(e.type())).toList();
        check("session log", back.size() == logged.size() && back.getLast().equals(logged.getLast().toJson()),
                back.size() + " lines, " + logged.size() + " logged events");
        check("event types", events.stream().map(Event::type).toList().containsAll(List.of("api.call", "permission.decided",
                "process.completed", "sandbox.limit_exceeded", "sandbox.watchdog", "model.delta", "agent.smoke")), "all present");
    }
}
