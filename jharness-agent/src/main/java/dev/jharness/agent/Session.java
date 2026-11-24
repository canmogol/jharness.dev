package dev.jharness.agent;

import dev.jharness.process.BuildOperations;
import dev.jharness.process.ChildSandbox;
import java.nio.file.Files;
import java.util.Map;
import java.util.List;
import java.net.URI;
import dev.jharness.net.http.HttpClients;
import dev.jharness.net.EgressProxy;
import dev.jharness.net.NetworkRules;
import dev.jharness.net.Rule;
import dev.jharness.net.RulesFile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import dev.jharness.config.ConfigException;
import dev.jharness.config.ModelProfile;
import dev.jharness.config.ModelProfiles;
import dev.jharness.config.Secrets;
import dev.jharness.config.Settings;
import dev.jharness.events.EventBus;
import dev.jharness.events.Json;
import dev.jharness.events.SessionLog;
import dev.jharness.llm.ModelProvider;
import dev.jharness.llm.ModelProviders;
import dev.jharness.process.Executable;
import dev.jharness.process.Executables;
import dev.jharness.process.GitOperations;
import dev.jharness.process.ProcessRunner;
import dev.jharness.process.Search;
import dev.jharness.sandbox.AgentIsolate;
import dev.jharness.sandbox.ApiServices;
import dev.jharness.sandbox.Checkpoints;
import dev.jharness.sandbox.IsolateManager;
import dev.jharness.sandbox.RunResult;
import dev.jharness.security.FilePolicy;
import dev.jharness.security.PermissionEngine;
import dev.jharness.security.PermissionGate;
import dev.jharness.security.PermissionMode;
import dev.jharness.security.ProjectRules;
import dev.jharness.security.Prompter;
import dev.jharness.security.Workspace;

/** Wires one session together: bus and log, workspace, tools, the main agent's isolate, provider and loop. */
public final class Session implements AutoCloseable {

    private final EventBus bus;
    private final SessionLog log;
    private final AgentIsolate isolate;
    private final AgentLoop loop;
    private final Workspace workspace;
    private final TurnCounter turns;
    private final Settings settings;
    private EgressProxy proxy;
    private Path sessionDir;
    private Secrets secrets;
    private String profile;
    private AgentDefinition agent;
    private SubagentRunner subagents;
    private Path home;
    private boolean trusted;

    private Session(EventBus bus, SessionLog log, AgentIsolate isolate, AgentLoop loop, Workspace workspace, TurnCounter turns,
            Settings settings) {
        this.bus = bus;
        this.log = log;
        this.isolate = isolate;
        this.loop = loop;
        this.workspace = workspace;
        this.turns = turns;
        this.settings = settings;
    }

    /**
     * How the session decides permissions: the permission engine from settings with a prompter (the
     * normal case), or a fixed gate (tests).
     */
    public record Access(PermissionGate gate, Prompter prompter, boolean trusted, AgentDefinition agent) {
        public static Access prompter(Prompter p) {
            return new Access(null, p, false, null);
        }

        public static Access gate(PermissionGate g) {
            return new Access(g, null, false, null);
        }

        /** Whether the project is trusted (D-011); recorded in {@code session.started}. */
        public Access trusted(boolean t) {
            return new Access(gate, prompter, t, agent);
        }

        /** The custom agent to run as (spec 025), or null for the built-in one. */
        public Access agent(AgentDefinition a) {
            return new Access(gate, prompter, trusted, a);
        }
    }

    /** Opens a session with the provider for {@code profileName} (null = the default profile). */
    public static Session open(Path home, Path workspace, Settings settings, String profileName, Access access)
            throws ConfigException, IOException {
        ModelProfile p = settings.models().resolve(profileName);
        Secrets secrets = Secrets.load(home, System::getenv);
        Session s = open(home, workspace, settings, access, (bus, turns) -> ModelProviders.create(p, bus, turns, secrets));
        s.secrets = secrets;
        s.profile = p.name();
        s.subagents.profile(p.name());
        return s;
    }

    /** Builds the provider once the bus exists; tests pass a replay provider. */
    public interface ProviderFactory {
        ModelProvider create(EventBus bus, TurnCounter turns) throws IOException;
    }

    public static Session open(Path home, Path workspacePath, Settings settings, Access access, ProviderFactory providers)
            throws IOException, ConfigException {
        Workspace ws = new Workspace(workspacePath);
        // Before anything starts, so a missing secret leaves nothing running (spec 017).
        Secrets loaded = Secrets.load(home, System::getenv);
        Map<String, String> tokens = gitTokens(settings, loaded);
        EventBus bus = new EventBus(EventBus.newSessionId());
        SessionLog log = SessionLog.open(home, ws.root(), bus.session());
        bus.subscribe(log);
        TurnCounter turns = new TurnCounter("main");
        EgressProxy proxy = startProxy(home, ws.root(), settings, access, bus, turns);
        ModelProvider provider = providers.create(bus, turns);
        AgentDefinition agent = access.agent();
        var started = Json.object().put("workspace", ws.root().toString())
                .put("provider", provider.provider()).put("model", provider.model()).put("trusted", access.trusted()).put("offline", settings.offline())
                .put("customAgent", agent == null ? "default" : agent.name());
        if (agent != null) {
            started.put("agentSource", agent.source());
        }
        bus.publish(turns.get(), "session.started", started);
        // <sessionDir>/tmp is the children's TMPDIR; <sessionDir>/net holds the proxy socket and Maven's settings (D-049).
        Path sessionDir = Files.createTempDirectory("jharness-");
        Path childTmp = Files.createDirectory(sessionDir.resolve("tmp"), OWNER_ONLY);
        Path netDir = Files.createDirectory(sessionDir.resolve("net"), OWNER_ONLY);
        ChildSandbox sandbox = ChildSandbox.detect(ChildSandbox.Policy.forSession(ws.root(), childTmp, home));
        if (sandbox.available()) {
            var cred = proxy.credentials();
            Path sock = netDir.toRealPath().resolve("proxy.sock");
            if (sandbox.mechanism() == ChildSandbox.Mechanism.BUBBLEWRAP) {
                proxy.serveUnix(sock);
            }
            sandbox = sandbox.withProxy(new ChildSandbox.ProxyRoute(proxy.address().getPort(), sock, cred.getUserName(),
                    new String(cred.getPassword())), ChildSandbox.selfBridgeCommand());
        }
        var os = Json.object().put("mechanism", sandbox.mechanism().wire()).put("available", sandbox.available())
                .put("tmp", sandbox.policy().tmp().toString());
        if (!sandbox.available()) {
            os.put("reason", sandbox.reason());
        }
        bus.publish(turns.get(), "sandbox.os", os);
        ProcessRunner runner = new ProcessRunner(bus, turns, settings.timeouts().outputChars(), sandbox);
        Optional<Executable> git = Executables.resolve("git", ws.root());
        GitOperations gitOps = git.map(g -> new GitOperations(runner, g, ws, settings.timeouts().process(), settings.timeouts().processMax(),
                settings.offline(), tokens)).orElse(null);
        BuildOperations builds = new BuildOperations(runner, ws, settings.timeouts(), access.trusted(), BuildOperations.findOnPath(ws.root()),
                home, System.getenv("JAVA_HOME"), settings.offline());
        FilePolicy files = new FilePolicy(ws, settings.files().mounts(), settings.files().deny());
        Search search = Search.create(ws, runner, settings.timeouts(), files.denyList());
        PermissionMode mode = agent != null && agent.plan() ? PermissionMode.PLAN : PermissionMode.of(settings.permissions().mode());
        PermissionGate gate = access.gate() != null ? access.gate()
                : new PermissionEngine(mode, ProjectRules.load(home, ws.root()),
                        dev.jharness.security.Unattended.guard(access.prompter()), bus, turns);
        Checkpoints checkpoints = new Checkpoints(Checkpoints.rootFor(home, ws.root(), bus.session()), bus, turns);
        var http = new dev.jharness.net.http.HttpOperations(bus, turns, settings.http(), settings.timeouts());
        if (agent != null && agent.tools() != null) {
            gate = new ToolsGate(gate, agent.name(), agent.tools());
        }
        SubagentRunner subagents = new SubagentRunner(home, ws.root(), access.trusted(), settings, loaded, bus, turns);
        ApiServices services = new ApiServices(files, gate, bus, turns, search, gitOps, checkpoints, builds, http, subagents);
        AgentIsolate isolate = new IsolateManager(settings.sandbox(), bus, turns).create("main", "main");
        RunResult ctx = isolate.run("api.readContext()", services);
        String contextJson = ctx.ok() ? ctx.value() : null;
        String projectMd = SystemPrompt.readMemory(ws.root().resolve("JHARNESS.md"));
        String userMd = SystemPrompt.readMemory(home.resolve(".jharness").resolve("JHARNESS.md"));
        String system = SystemPrompt.build(contextJson, projectMd, userMd, agent);
        if (agent != null && agent.helpers() != null) {
            isolate.useHelpers(agent.helpers());
        }
        AgentLoop loop = new AgentLoop(provider, isolate, services, bus, turns, system,
                agent != null && agent.maxSteps() != null ? agent.maxSteps() : settings.agent().maxSteps(),
                settings.timeouts().outputChars());
        loop.useCheckpoints(checkpoints);
        subagents.bind(services, loop, contextJson, projectMd, userMd);
        loop.useNotes(subagents::drainFinished);
        Session s = new Session(bus, log, isolate, loop, ws, turns, settings);
        s.agent = agent;
        s.subagents = subagents;
        s.home = home;
        s.trusted = access.trusted();
        s.proxy = proxy;
        s.sessionDir = sessionDir;
        return s;
    }

    /** The tokens for {@code git.credentials} (spec 017, AC-06); a mapped secret that isn't set stops start-up. */
    static Map<String, String> gitTokens(Settings settings, Secrets secrets) throws ConfigException {
        Map<String, String> tokens = new java.util.TreeMap<>();
        for (Map.Entry<String, String> e : settings.git().credentials().entrySet()) {
            String v = secrets.apply(e.getValue());
            if (v == null || v.isBlank()) {
                throw new ConfigException("git.credentials." + e.getKey() + ": secret " + e.getValue()
                        + " is not set (set it in the environment or in ~/.jharness/secrets)");
            }
            tokens.put(e.getKey(), v.strip());
        }
        return tokens;
    }

    public static Path generalNetworkRules(Path home) {
        return home.resolve(".jharness").resolve("network.rules");
    }

    public static Path projectNetworkRules(Path workspace) {
        return workspace.resolve(".jharness").resolve("network.rules");
    }

    /** Starts the egress proxy (D-025, spec 011) and points every HTTP client the session builds at it. */
    private static EgressProxy startProxy(Path home, Path root, Settings settings, Access access, EventBus bus, TurnCounter turns)
            throws IOException, ConfigException {
        Path general = generalNetworkRules(home);
        Path project = projectNetworkRules(root);
        boolean offline = settings.offline();
        // Offline, only model endpoints on this machine or a private network are allowed automatically (D-055).
        List<Rule> endpoints = modelEndpoints(settings.models()).stream()
                .filter(r -> !offline || NetworkRules.local(r.target().toString())).toList();
        boolean trusted = access.trusted();
        EgressProxy p = EgressProxy.start(() -> new NetworkRules(RulesFile.load(general), RulesFile.load(project), trusted, endpoints, offline),
                access.prompter(), bus, turns, project);
        HttpClients.useProxy(p.address(), p.credentials());
        return p;
    }

    /** Every profile's endpoint is allowed automatically, so model traffic works without rule edits (D-025). */
    static List<Rule> modelEndpoints(ModelProfiles models) {
        List<Rule> l = new java.util.ArrayList<>();
        for (Map.Entry<String, ModelProfile> e : models.all().entrySet()) {
            URI u = URI.create(e.getValue().baseUrl());
            String host = u.getHost();
            if (host == null) {
                continue;
            }
            int port = u.getPort() > 0 ? u.getPort() : "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
            String h = host.startsWith("[") ? host : host.contains(":") ? "[" + host + "]" : host;
            l.add(RulesFile.rule(Rule.Action.ALLOW, h + ":" + port, "model profile " + e.getKey()));
        }
        return List.copyOf(l);
    }

    /** Whether the session runs in offline mode (D-033, D-055). */
    public boolean offline() {
        return settings.offline();
    }

    /** The session's egress proxy, for {@code /network}. */
    public EgressProxy proxy() {
        return proxy;
    }

    /** The profiles that {@link #switchModel} can pick from. */
    public ModelProfiles models() {
        return settings.models();
    }

    /** The current profile's name, or null when the session was opened with a provider factory (tests, replay). */
    public String profile() {
        return profile;
    }

    /** {@code /model NAME}: use another profile for the rest of the session; the conversation is kept (D-032). */
    public synchronized void switchModel(String name) throws ConfigException {
        ModelProfile p = settings.models().resolve(name);
        Secrets s = secrets != null ? secrets : Secrets.load(Path.of(System.getProperty("user.home")), System::getenv);
        ModelProvider provider = ModelProviders.create(p, bus, turns, s);
        loop.useProvider(provider);
        profile = p.name();
        subagents.profile(p.name());
        bus.publish(turns.get(), "session.model_changed", Json.object().put("profile", p.name())
                .put("provider", provider.provider()).put("model", provider.model()));
    }

    /** The custom agent this session runs as, or null for the built-in one (spec 025). */
    public AgentDefinition agent() {
        return agent;
    }

    /** {@code /agent}: the agents this session could start with, the current one marked. */
    public List<String> describeAgents() {
        return AgentDefinition.describe(home, workspace.root(), trusted, agent == null ? null : agent.name());
    }

    /** Tests: the session's subagent runner. */
    SubagentRunner runner() {
        return subagents;
    }

    /** {@code /agents}: this session's subagents, oldest first (spec 027). */
    public List<Subagent> subagents() {
        return subagents.list().stream().map(i -> new Subagent(i.name(), i.task(), i.customAgent(), i.model(), i.background(), i.status(),
                i.steps(), i.durationMs())).toList();
    }

    /** One subagent for {@code /agents}; {@code status} is {@code running} or how it ended. */
    public record Subagent(String name, String task, String customAgent, String model, boolean background, String status, int steps,
            long durationMs) {}

    /** The background subagents still running; {@code /undo} and {@code /rewind} wait for them (spec 027). */
    public List<String> runningSubagents() {
        return subagents.running();
    }

    /** {@code /stop NAME}; false if no running subagent has that name. */
    public boolean stopSubagent(String name) {
        return subagents.stop(name);
    }

    /** {@code /stop all}: the names of the subagents it stopped. */
    public List<String> stopSubagents() {
        return subagents.stopAll();
    }

    public EventBus bus() {
        return bus;
    }

    public AgentLoop loop() {
        return loop;
    }

    public Workspace workspace() {
        return workspace;
    }

    public Path logPath() {
        return log.path();
    }

    public Settings settings() {
        return settings;
    }

    @Override
    public void close() throws IOException {
        subagents.stopAll();
        isolate.close();
        if (proxy != null) {
            HttpClients.stopProxy(proxy.address());
            proxy.close();
        }
        if (sessionDir != null) {
            deleteTree(sessionDir);
        }
        log.close();
    }

    private static final java.nio.file.attribute.FileAttribute<?> OWNER_ONLY =
            java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));

    /** Removes the session folder (children's temp folder, proxy socket, Maven settings); best effort. */
    private static void deleteTree(Path root) {
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(f -> {
                try {
                    Files.deleteIfExists(f);
                } catch (IOException e) {
                    // left for the OS to clean
                }
            });
        } catch (IOException e) {
            // already gone
        }
    }
}
