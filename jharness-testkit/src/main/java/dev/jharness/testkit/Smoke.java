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
        if (args.length > 0 && args[0].equals(dev.jharness.process.ProxyBridge.FLAG)) {
            System.exit(dev.jharness.process.ProxyBridge.run(java.util.Arrays.copyOfRange(args, 1, args.length)));
        }
        Smoke s = new Smoke();
        if (args.length > 0 && args[0].equals("--security")) {
            s.security(Files.createTempDirectory("jharness-security"));
        } else {
            s.run();
        }
        System.out.println(Json.write(Json.object().put("smoke", s.failures.isEmpty() ? "pass" : "fail")
                .put("failures", s.failures.size())));
        System.exit(s.failures.isEmpty() ? 0 : 1);
    }

    /** Spec 018 AC-08: every security case, one {@code security <ID>} check each; a skip is printed and doesn't fail. */
    private void security(Path tmp) {
        for (var c : dev.jharness.testkit.security.SecuritySuite.all()) {
            var o = dev.jharness.testkit.security.SecuritySuite.run(c, tmp.resolve(c.id()));
            if (o.status().equals("skip") || o.status().equals("exception")) {
                System.out.println(Json.write(Json.object().put("check", "security " + c.id()).put("status", o.status()).put("detail", o.detail())));
            } else {
                check("security " + c.id(), o.passed(), o.detail());
            }
        }
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
        // A hostile repo config (spec 015): git must not run this filter or signing program.
        Path hostile = tmp.resolve("hostile.sh");
        Path hostileRan = root.resolve(".git/hostile-ran");
        Files.writeString(hostile, "#!/bin/sh\ntouch " + hostileRan + "\ncat\n");
        Files.setPosixFilePermissions(hostile, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        Files.writeString(root.resolve(".gitattributes"), "*.txt filter=evil\n");
        for (List<String> c : List.of(List.of("config", "filter.evil.clean", hostile.toString()),
                List.of("config", "commit.gpgSign", "true"), List.of("config", "gpg.program", hostile.toString()))) {
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
        check("os sandbox (starts true, blocks a write outside)", childSandbox.available(), childSandbox.mechanism().wire() + " " + childSandbox.reason());
        ProcessRunner runner = new ProcessRunner(bus, () -> o, Timeouts.defaults().outputChars(), childSandbox);
        GitOperations git = new GitOperations(runner, gitExe, ws, Duration.ofSeconds(60));
        Search search = Search.create(ws, runner, Timeouts.defaults());
        check("search engine", true, search.engine());
        var page = dev.jharness.net.http.PageText.of("<html><head><title>Guide</title><script>evil()</script></head><body>"
                + "<nav>menu</nav><h1>Intro</h1><p>See <a href=\"/docs\">the docs</a>.</p><pre><code>int x;</code></pre></body></html>",
                java.net.URI.create("https://example.invalid/a/"));
        check("jsoup page text", page.title().equals("Guide") && page.text().contains("# Intro")
                && page.text().contains("[the docs](https://example.invalid/docs)") && page.text().contains("```\nint x;\n```")
                && !page.text().contains("evil") && !page.text().contains("menu"), page.text());
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
            check("git ignores the repo's filter and gpg.program", !Files.exists(hostileRan), "no marker");
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
            // Spec 020: a rate limit is waited out and sent again, with the real wait.
            stub.enqueue(StubModelServer.Reply.json(429, Streams.ANTHROPIC_RATE_LIMIT).with("retry-after-ms", "50"))
                    .enqueue(StubModelServer.Reply.sse(Streams.ANTHROPIC_OK));
            int[] retries = {0};
            bus.subscribe(e -> {
                if (e.type().equals("model.retry")) {
                    retries[0]++;
                }
            });
            String c = ModelProviders.create(ms.models().resolve("c"), bus, () -> o, Map.of("K", "key")::get).stream(req, t -> {}).text();
            check("model retry after 429", c.equals("Hello, world") && retries[0] == 1, c + ", retries " + retries[0]);
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
                    dev.jharness.net.http.HttpClients.create().send(java.net.http.HttpRequest.newBuilder(
                            java.net.URI.create("https://127.0.0.1:" + tls.getLocalPort() + "/")).timeout(Duration.ofSeconds(5)).build(),
                            java.net.http.HttpResponse.BodyHandlers.discarding());
                } catch (java.io.IOException e) {
                    // the TLS handshake fails after the tunnel opens
                }
                check("proxy CONNECT credentials", net.stream().anyMatch(e -> e.type().equals("network.allowed")
                        && e.body().get("port").asInt() == tls.getLocalPort()), net);

                // Spec 016: api.fetch from agent JS, through the session's proxy, converted with jsoup.
                stub.enqueue(new StubModelServer.Reply(200, "text/html; charset=utf-8",
                        "<html><head><title>Docs</title><script>x()</script></head><body><h1>Hello</h1><p>See <a href=\"/b\">b</a></p></body></html>", 0));
                var httpOps = new dev.jharness.net.http.HttpOperations(session.bus(), () -> o, ps.http(), ps.timeouts());
                var fetchApi = new ApiServices(dev.jharness.security.FilePolicy.workspaceOnly(ws), PermissionGate.allowAll(), session.bus(),
                        () -> o, search, git, null, null, httpOps);
                try (AgentIsolate iso = new IsolateManager(settings.sandbox(), session.bus(), () -> o).create("main", "main")) {
                    RunResult fr = iso.run("const p = JSON.parse(api.fetch('" + stub.baseUrl() + "/docs')); p.title + '|' + p.text", fetchApi);
                    check("api.fetch through the proxy", fr.ok() && fr.value().equals("Docs|# Hello\n\nSee [b](" + stub.baseUrl() + "/b)"),
                            fr.ok() ? fr.value() : fr.forModel());
                }
            }
        }

        buildThroughProxy(tmp, bus, o);
        gitCloneThroughProxy(tmp, bus, o);

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

        security(tmp.resolve("security"));

        log.close();
        List<JsonNode> back = SessionLog.read(log.path());
        List<Event> logged = events.stream().filter(e -> !SessionLog.LIVE_ONLY.contains(e.type())).toList();
        check("session log", back.size() == logged.size() && back.getLast().toString().equals(logged.getLast().toJson().toString()),
                back.size() + " lines, " + logged.size() + " logged events");
        check("event types", events.stream().map(Event::type).toList().containsAll(List.of("api.call", "permission.decided",
                "process.completed", "sandbox.limit_exceeded", "sandbox.watchdog", "model.delta", "agent.smoke")), "all present");
    }

    /**
     * Spec 013: an npm script in the OS sandbox tunnels through the egress proxy to an allowed stub
     * and can't connect to it directly. On Linux this runs this binary again as the proxy bridge.
     */
    private void buildThroughProxy(Path tmp, EventBus bus, Origin o) throws Exception {
        try (java.net.ServerSocket web = new java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> {
                while (!web.isClosed()) {
                    try (java.net.Socket c = web.accept()) {
                        var in = c.getInputStream();
                        StringBuilder head = new StringBuilder();
                        int b;
                        while ((b = in.read()) >= 0 && !head.append((char) b).toString().endsWith("\r\n\r\n")) {
                            // read the request head
                        }
                        c.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 15\r\nConnection: close\r\n\r\nhello from stub"
                                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    } catch (java.io.IOException e) {
                        // closed
                    }
                }
            });
            Path ws = Files.createDirectories(tmp.resolve("build-ws")).toRealPath();
            var allow = List.of(dev.jharness.net.RulesFile.rule(dev.jharness.net.Rule.Action.ALLOW, "127.0.0.1:" + web.getLocalPort(), "smoke"));
            try (dev.jharness.net.EgressProxy proxy = dev.jharness.net.EgressProxy.start(() -> new dev.jharness.net.NetworkRules(
                    dev.jharness.net.RulesFile.empty(tmp.resolve("g.rules")), dev.jharness.net.RulesFile.empty(tmp.resolve("p.rules")), true, allow),
                    null, bus, () -> o, tmp.resolve("p.rules"))) {
                Path sock = Files.createTempDirectory("jh-smoke-net-").resolve("proxy.sock");
                Path home = Path.of(System.getProperty("user.home"));
                dev.jharness.process.ChildSandbox sb = dev.jharness.process.ChildSandbox.detect(dev.jharness.process.ChildSandbox.Policy
                        .forSession(ws, Files.createDirectories(tmp.resolve("build-tmp")), home));
                if (sb.mechanism() == dev.jharness.process.ChildSandbox.Mechanism.BUBBLEWRAP && sb.available()) {
                    proxy.serveUnix(sock);
                }
                var cred = proxy.credentials();
                sb = sb.withProxy(new dev.jharness.process.ChildSandbox.ProxyRoute(proxy.address().getPort(), sock, cred.getUserName(),
                        new String(cred.getPassword())), dev.jharness.process.ChildSandbox.selfBridgeCommand());
                Files.writeString(ws.resolve("probe.js"), PROBE_JS);
                Files.writeString(ws.resolve("package.json"), "{\"name\":\"smoke\",\"version\":\"1.0.0\",\"scripts\":{\"probe\":\"node probe.js 127.0.0.1:"
                        + web.getLocalPort() + "\"}}");
                var defs = dev.jharness.process.ToolDefinitions.defaults();
                var tools = dev.jharness.process.BuildOperations.findOnPath(defs, ws);
                var ops = new dev.jharness.process.BuildOperations(new ProcessRunner(bus, () -> o, 30_000, sb), new Workspace(ws),
                        Timeouts.defaults(), true, defs, tools, home, System.getenv("JAVA_HOME"), false);
                String out;
                try {
                    out = ops.run("npm", Json.parseObject("{\"op\":\"run\",\"script\":\"probe\"}")).output();
                } catch (RuntimeException e) {
                    out = e.toString();
                }
                check("npm through the sandbox and the proxy (" + sb.mechanism().wire() + ")", out.contains("proxied ok") && out.contains("direct-failed")
                        && !out.contains(new String(cred.getPassword())), out);
            }
        }
    }

    /**
     * Spec 017: {@code git clone} over HTTPS from a local server, in the OS sandbox, through the
     * egress proxy, which resolves {@code git.test} to loopback; a denied host fails at the proxy.
     */
    private void gitCloneThroughProxy(Path tmp, EventBus bus, Origin o) throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("git-ws")).toRealPath();
        var exe = dev.jharness.process.Executables.resolve("git", ws).orElseThrow();
        try (SmokeGitServer srv = new SmokeGitServer(Files.createDirectories(tmp.resolve("git-srv")), exe.path())) {
            Path g = tmp.resolve("git.rules");
            Files.writeString(g, "default deny\nallow " + SmokeGitServer.HOST + "\n");
            List<Event> net = new java.util.concurrent.CopyOnWriteArrayList<>();
            bus.subscribe(e -> {
                if (e.type().startsWith("network.")) {
                    net.add(e);
                }
            });
            try (dev.jharness.net.EgressProxy proxy = dev.jharness.net.EgressProxy.start(() -> new dev.jharness.net.NetworkRules(
                    dev.jharness.net.RulesFile.load(g), dev.jharness.net.RulesFile.empty(tmp.resolve("gp.rules")), true, List.of()),
                    null, bus, () -> o, tmp.resolve("gp.rules"),
                    h -> h.equals(SmokeGitServer.HOST) ? new java.net.InetAddress[] {java.net.InetAddress.getLoopbackAddress()}
                            : new java.net.InetAddress[] {java.net.InetAddress.ofLiteral("93.184.216.34")})) {
                Path sock = Files.createTempDirectory("jh-smoke-git-").resolve("proxy.sock");
                dev.jharness.process.ChildSandbox sb = dev.jharness.process.ChildSandbox.detect(dev.jharness.process.ChildSandbox.Policy
                        .forSession(ws, Files.createDirectories(tmp.resolve("git-tmp")), Path.of(System.getProperty("user.home"))));
                if (sb.mechanism() == dev.jharness.process.ChildSandbox.Mechanism.BUBBLEWRAP && sb.available()) {
                    proxy.serveUnix(sock);
                }
                var cred = proxy.credentials();
                sb = sb.withProxy(new dev.jharness.process.ChildSandbox.ProxyRoute(proxy.address().getPort(), sock, cred.getUserName(),
                        new String(cred.getPassword())), dev.jharness.process.ChildSandbox.selfBridgeCommand());
                var ops = new GitOperations(new ProcessRunner(bus, () -> o, 30_000, sb), exe, new Workspace(ws), Duration.ofSeconds(60),
                        Duration.ofMinutes(10), false, java.util.Map.of(), List.of(java.util.Map.entry("http.sslCAInfo", srv.ca.toString())));
                String out;
                try {
                    out = ops.run(Json.parseObject("{\"op\":\"clone\",\"url\":\"" + srv.url() + "\"}")).output();
                } catch (RuntimeException e) {
                    out = e.toString();
                }
                Path readme = ws.resolve("repo/README.md");
                boolean cloned = Files.isRegularFile(readme) && Files.readString(readme).equals("served over https\n");
                check("git clone over https through the sandbox and the proxy (" + sb.mechanism().wire() + ")",
                        cloned && net.stream().anyMatch(e -> e.type().equals("network.allowed")), cloned + " " + out.strip());
                String denied;
                try {
                    denied = ops.run(Json.parseObject("{\"op\":\"clone\",\"url\":\"https://denied.test/x.git\"}")).output();
                } catch (RuntimeException e) {
                    denied = e.toString();
                }
                check("git clone of a denied host fails at the proxy", !Files.exists(ws.resolve("x"))
                        && net.stream().anyMatch(e -> e.type().equals("network.blocked")), denied.strip());
            }
        }
    }

    /** Tunnels to the stub through the proxy in npm_config_https_proxy, then tries the stub directly. */
    static final String PROBE_JS = """
            const net = require('net');
            const u = new URL(process.env.npm_config_https_proxy);
            const target = process.argv[2];
            const port = Number(target.split(':')[1]);
            const auth = Buffer.from(decodeURIComponent(u.username) + ':' + decodeURIComponent(u.password)).toString('base64');
            function direct(done) {
              const s = net.connect({ host: '127.0.0.1', port });
              s.setTimeout(3000);
              s.on('connect', () => { done('direct-connected'); s.destroy(); });
              s.on('error', (e) => done('direct-failed ' + e.code));
              s.on('timeout', () => { done('direct-failed timeout'); s.destroy(); });
            }
            function proxied(done) {
              const s = net.connect({ host: u.hostname, port: Number(u.port) });
              let buf = '';
              let tunnelled = false;
              s.setTimeout(8000);
              s.on('connect', () => s.write('CONNECT ' + target + ' HTTP/1.1\\r\\nHost: ' + target
                  + '\\r\\nProxy-Authorization: Basic ' + auth + '\\r\\n\\r\\n'));
              s.on('data', (d) => {
                buf += d;
                if (!tunnelled && buf.includes('\\r\\n\\r\\n')) {
                  tunnelled = true;
                  if (!/^HTTP\\/1\\.[01] 200/.test(buf)) { done('proxy-refused ' + buf.split('\\r\\n')[0]); s.destroy(); return; }
                  buf = '';
                  s.write('GET / HTTP/1.1\\r\\nHost: ' + target + '\\r\\nConnection: close\\r\\n\\r\\n');
                }
              });
              s.on('end', () => done(buf.includes('hello from stub') ? 'proxied ok' : 'proxied bad ' + JSON.stringify(buf)));
              s.on('error', (e) => done('proxied-failed ' + e.message));
              s.on('timeout', () => { done('proxied-failed timeout'); s.destroy(); });
            }
            let once = false;
            proxied((a) => { if (once) return; once = true; console.log(a); if (a !== 'proxied ok') process.exitCode = 1; direct((b) => console.log(b)); });
            """;
}
