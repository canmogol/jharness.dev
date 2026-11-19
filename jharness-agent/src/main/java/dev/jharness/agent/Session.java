package dev.jharness.agent;

import java.util.Map;
import java.util.List;
import java.net.URI;
import dev.jharness.llm.http.HttpClients;
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
    private Secrets secrets;
    private String profile;

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
    public record Access(PermissionGate gate, Prompter prompter, boolean trusted) {
        public static Access prompter(Prompter p) {
            return new Access(null, p, false);
        }

        public static Access gate(PermissionGate g) {
            return new Access(g, null, false);
        }

        /** Whether the project is trusted (D-011); recorded in {@code session.started}. */
        public Access trusted(boolean t) {
            return new Access(gate, prompter, t);
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
        return s;
    }

    /** Builds the provider once the bus exists; tests pass a replay provider. */
    public interface ProviderFactory {
        ModelProvider create(EventBus bus, TurnCounter turns) throws IOException;
    }

    public static Session open(Path home, Path workspacePath, Settings settings, Access access, ProviderFactory providers)
            throws IOException, ConfigException {
        Workspace ws = new Workspace(workspacePath);
        EventBus bus = new EventBus(EventBus.newSessionId());
        SessionLog log = SessionLog.open(home, ws.root(), bus.session());
        bus.subscribe(log);
        TurnCounter turns = new TurnCounter("main");
        EgressProxy proxy = startProxy(home, ws.root(), settings, access, bus, turns);
        ModelProvider provider = providers.create(bus, turns);
        bus.publish(turns.get(), "session.started", Json.object().put("workspace", ws.root().toString())
                .put("provider", provider.provider()).put("model", provider.model()).put("trusted", access.trusted()));
        ProcessRunner runner = new ProcessRunner(bus, turns, settings.timeouts().outputChars());
        Optional<Executable> git = Executables.resolve("git", ws.root());
        GitOperations gitOps = git.map(g -> new GitOperations(runner, g, ws, settings.timeouts().process())).orElse(null);
        FilePolicy files = new FilePolicy(ws, settings.files().mounts(), settings.files().deny());
        Search search = Search.create(ws, bus, turns, settings.timeouts(), files.denyList());
        PermissionGate gate = access.gate() != null ? access.gate()
                : new PermissionEngine(PermissionMode.of(settings.permissions().mode()), ProjectRules.load(home, ws.root()),
                        access.prompter(), bus, turns);
        Checkpoints checkpoints = new Checkpoints(Checkpoints.rootFor(home, ws.root(), bus.session()), bus, turns);
        ApiServices services = new ApiServices(files, gate, bus, turns, search, gitOps, checkpoints);
        AgentIsolate isolate = new IsolateManager(settings.sandbox(), bus, turns).create("main", "main");
        RunResult ctx = isolate.run("api.readContext()", services);
        String system = SystemPrompt.build(ctx.ok() ? ctx.value() : null, SystemPrompt.readMemory(ws.root().resolve("JHARNESS.md")),
                SystemPrompt.readMemory(home.resolve(".jharness").resolve("JHARNESS.md")));
        AgentLoop loop = new AgentLoop(provider, isolate, services, bus, turns, system, settings.agent().maxSteps(),
                settings.timeouts().outputChars());
        loop.useCheckpoints(checkpoints);
        Session s = new Session(bus, log, isolate, loop, ws, turns, settings);
        s.proxy = proxy;
        return s;
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
        List<Rule> endpoints = modelEndpoints(settings.models());
        boolean trusted = access.trusted();
        EgressProxy p = EgressProxy.start(() -> new NetworkRules(RulesFile.load(general), RulesFile.load(project), trusted, endpoints),
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
        bus.publish(turns.get(), "session.model_changed", Json.object().put("profile", p.name())
                .put("provider", provider.provider()).put("model", provider.model()));
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
        isolate.close();
        if (proxy != null) {
            HttpClients.stopProxy(proxy.address());
            proxy.close();
        }
        log.close();
    }
}
