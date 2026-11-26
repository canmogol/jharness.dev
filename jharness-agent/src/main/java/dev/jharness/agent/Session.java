package dev.jharness.agent;

import dev.jharness.process.BuildOperations;
import dev.jharness.process.ToolDefinitions;
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
    private ToolDefinitions tools;
    private HookRunner hooks;
    private ApiServices services;
    private PlanState plans;
    private dev.jharness.mcp.McpServers mcp;
    private dev.jharness.net.review.SystemOneReviewer reviewer;
    private volatile CommandRunner commands;
    private SubagentRunner subagents;
    private Path home;
    private boolean trusted;
    private FilePolicy files;
    private PermissionEngine engine;
    private SessionState.Resume resumed;
    private String startMode;

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
    public record Access(PermissionGate gate, Prompter prompter, boolean trusted, AgentDefinition agent, boolean projectTools,
            boolean interactive, SessionState.Resume resume) {
        public static Access prompter(Prompter p) {
            return new Access(null, p, false, null, true, false, null);
        }

        public static Access gate(PermissionGate g) {
            return new Access(g, null, false, null, true, false, null);
        }

        /** Whether the project is trusted (D-011); recorded in {@code session.started}. */
        public Access trusted(boolean t) {
            return new Access(gate, prompter, t, agent, projectTools, interactive, resume);
        }

        /** The custom agent to run as (spec 025), or null for the built-in one. */
        public Access agent(AgentDefinition a) {
            return new Access(gate, prompter, trusted, a, projectTools, interactive, resume);
        }

        /** Whether a trusted project's own tools count; false when the user didn't confirm their changes (D-066). */
        public Access projectTools(boolean use) {
            return new Access(gate, prompter, trusted, agent, use, interactive, resume);
        }

        /** A person can switch to a subagent to answer a held question (the terminal UI, spec 033). */
        public Access interactive(boolean i) {
            return new Access(gate, prompter, trusted, agent, projectTools, i, resume);
        }

        /** Start from a saved session (spec 043): continue it, or fork a new one from it; null for a new session. */
        public Access resume(SessionState.Resume r) {
            return new Access(gate, prompter, trusted, agent, projectTools, interactive, r);
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
        String reviewerKey = reviewerKey(settings, loaded);
        SessionState.Resume resume = access.resume();
        String id;
        if (resume != null && !resume.fork()) {
            // --resume goes on in the same session: its log, its id, its checkpoint folder (spec 043).
            id = resume.state().session();
        } else {
            id = EventBus.newSessionId();
            while (Files.exists(SessionLog.pathFor(home, ws.root(), id))) {
                id = EventBus.newSessionId();
            }
        }
        EventBus bus = new EventBus(id);
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
        ToolDefinitions.seed(home);
        ToolDefinitions tools = ToolDefinitions.load(home, ws.root(), access.trusted() && access.projectTools());
        var toolsEvent = Json.object();
        var names = toolsEvent.putArray("tools");
        tools.tools().values().forEach(d -> names.addObject().put("name", d.name()).put("source", d.source().wire()));
        if (!tools.errors().isEmpty()) {
            var errs = toolsEvent.putObject("errors");
            tools.errors().forEach(errs::put);
        }
        toolsEvent.put("projectTools", access.trusted() && access.projectTools());
        bus.publish(turns.get(), "tools.loaded", toolsEvent);
        BuildOperations builds = new BuildOperations(runner, ws, settings.timeouts(), access.trusted(), tools, BuildOperations.findOnPath(tools, ws.root()),
                home, System.getenv("JAVA_HOME"), settings.offline());
        FilePolicy files = new FilePolicy(ws, settings.files().mounts(), settings.files().deny());
        Search search = Search.create(ws, runner, settings.timeouts(), files.denyList());
        PermissionMode mode = agent != null && agent.plan() ? PermissionMode.PLAN : PermissionMode.of(settings.permissions().mode());
        PermissionEngine engine = access.gate() != null ? null
                : new PermissionEngine(mode, ProjectRules.load(home, ws.root()),
                        dev.jharness.security.Unattended.guard(access.prompter()), bus, turns);
        PermissionGate gate = access.gate() != null ? access.gate() : engine;
        dev.jharness.net.review.SystemOneReviewer reviewer = null;
        if (engine != null && settings.reviewer().enabled()) {
            reviewer = new dev.jharness.net.review.SystemOneReviewer(settings.reviewer(), reviewerKey, bus, turns,
                    ws.root().getFileName() == null ? ws.root().toString() : ws.root().getFileName().toString());
            engine.useReviewer(reviewer);
            bus.publish(turns.get(), "reviewer.ready", Json.object().put("url", settings.reviewer().url()).put("model", settings.reviewer().model())
                    .put("askBelow", settings.reviewer().askBelow()).put("denyBelow", settings.reviewer().denyBelow())
                    .put("timeoutMs", settings.reviewer().timeout().toMillis()).put("onError", settings.reviewer().onError()));
        }
        PlanState plans = new PlanState(bus, engine, access.prompter(), agent != null && agent.plan());
        Checkpoints checkpoints = new Checkpoints(Checkpoints.rootFor(home, ws.root(), bus.session()), bus, turns);
        var http = new dev.jharness.net.http.HttpOperations(bus, turns, settings.http(), settings.timeouts());
        if (agent != null && agent.tools() != null) {
            gate = new ToolsGate(gate, agent.name(), agent.tools());
        }
        SubagentRunner subagents = new SubagentRunner(home, ws.root(), access.trusted(), settings, loaded, bus, turns);
        dev.jharness.mcp.McpServers mcp = settings.mcp().servers().isEmpty() ? null
                : dev.jharness.mcp.McpServers.start(settings.mcp(), runner, ws.root(), home, loaded::apply, bus, turns);
        ApiServices base = new ApiServices(files, gate, bus, turns, search, gitOps, checkpoints, builds, http, null, null, null, mcp);
        HookRunner hooks = HookRunner.load(home, ws.root(), access.trusted(), settings, bus, turns, base);
        ApiServices services = new ApiServices(files, gate, bus, turns, search, gitOps, checkpoints, builds, http, subagents,
                hooks.any() ? hooks : null, plans, mcp);
        AgentIsolate isolate = new IsolateManager(settings.sandbox(), bus, turns).create("main", "main");
        RunResult ctx = isolate.run("api.readContext()", services);
        String contextJson = ctx.ok() ? ctx.value() : null;
        String projectMd = SystemPrompt.readMemory(ws.root().resolve("JHARNESS.md"));
        String userMd = SystemPrompt.readMemory(home.resolve(".jharness").resolve("JHARNESS.md"));
        String toolsText = SystemPrompt.tools(builds.list()) + (mcp == null ? "" : SystemPrompt.mcp(dev.jharness.events.Json.write(mcp.list(null))));
        String system = SystemPrompt.build(contextJson, projectMd, userMd, agent) + toolsText;
        if (agent != null && agent.helpers() != null) {
            isolate.useHelpers(agent.helpers());
        }
        AgentLoop loop = new AgentLoop(provider, isolate, services, bus, turns, system,
                agent != null && agent.maxSteps() != null ? agent.maxSteps() : settings.agent().maxSteps(),
                settings.timeouts().outputChars());
        loop.useCheckpoints(checkpoints);
        loop.useCompaction(settings.agent().autoCompact(), settings.agent().compactAt());
        if (hooks.any()) {
            loop.useStopHook(hooks);
        }
        subagents.bind(services, loop, contextJson, projectMd, userMd, toolsText);
        loop.useNotes(() -> {
            String plan = plans.note();
            String done = subagents.drainFinished();
            return plan.isEmpty() ? done : done.isEmpty() ? plan : plan + "\n\n" + done;
        });
        subagents.holdQuestions(access.interactive());
        if (resume != null) {
            SessionState st = resume.state();
            loop.restore(new AgentLoop.Snapshot(st.conversation(), st.turn(), st.compactedThrough(), st.firstPrompt()));
            bus.publish(turns.get(), "session.resumed", Json.object().put("from", st.session()).put("fork", resume.fork())
                    .put("turns", st.turn()).put("messages", st.conversation().size()).put("savedProfile", st.profile())
                    .put("savedModel", st.model()));
        }
        Session s = new Session(bus, log, isolate, loop, ws, turns, settings);
        String forkedFrom = resume == null ? null : resume.fork() ? resume.state().session() : resume.state().forkedFrom();
        loop.useStateSink(snap -> {
            try {
                new SessionState(bus.session(), ws.root().toString(), s.profile, loop.provider().model(), snap.turn(), snap.conversation(),
                        snap.compactedThrough(), forkedFrom, java.time.Instant.now().toString(), snap.firstPrompt()).save(home, ws.root());
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        if (resume != null && resume.fork()) {
            // A fork has its own state file from the start, so it can be resumed before its first turn ends.
            loop.saveState();
        }
        s.agent = agent;
        s.tools = tools;
        s.hooks = hooks;
        s.services = services;
        s.plans = plans;
        s.mcp = mcp;
        s.reviewer = reviewer;
        s.subagents = subagents;
        s.home = home;
        s.trusted = access.trusted();
        s.files = files;
        s.engine = engine;
        s.resumed = resume;
        s.startMode = mode.name().toLowerCase().replace('_', '-');
        s.proxy = proxy;
        s.sessionDir = sessionDir;
        return s;
    }

    /** The reviewer's bearer token (spec 038); a named secret that isn't set stops start-up. */
    static String reviewerKey(Settings settings, Secrets secrets) throws ConfigException {
        var r = settings.reviewer();
        if (!r.enabled() || r.apiKeyEnv() == null) {
            return null;
        }
        String v = secrets.apply(r.apiKeyEnv());
        if (v == null || v.isBlank()) {
            throw new ConfigException("reviewer.apiKeyEnv: secret " + r.apiKeyEnv()
                    + " is not set (set it in the environment or in ~/.jharness/secrets, or turn the reviewer off)");
        }
        return v.strip();
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
        List<Rule> endpoints = java.util.stream.Stream.of(modelEndpoints(settings.models()), mcpEndpoints(settings.mcp()),
                        reviewerEndpoint(settings.reviewer())).flatMap(List::stream)
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

    /** The user's HTTP MCP servers are allowed automatically, like model endpoints (D-076). */
    static List<Rule> mcpEndpoints(dev.jharness.config.McpSettings mcp) {
        List<Rule> l = new java.util.ArrayList<>();
        for (var srv : mcp.servers().values()) {
            if (srv.url() == null) {
                continue;
            }
            URI u = URI.create(srv.url());
            int port = u.getPort() > 0 ? u.getPort() : "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
            String host = u.getHost();
            String h = host.startsWith("[") ? host : host.contains(":") ? "[" + host + "]" : host;
            l.add(RulesFile.rule(Rule.Action.ALLOW, h + ":" + port, "MCP server " + srv.name()));
        }
        return List.copyOf(l);
    }

    /** The reviewer's endpoint is allowed automatically, like model endpoints (D-031). */
    static List<Rule> reviewerEndpoint(dev.jharness.config.ReviewerSettings r) {
        if (!r.enabled()) {
            return List.of();
        }
        URI u = URI.create(r.url());
        int port = u.getPort() > 0 ? u.getPort() : "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
        String host = u.getHost();
        String h = host.startsWith("[") ? host : host.contains(":") ? "[" + host + "]" : host;
        return List.of(RulesFile.rule(Rule.Action.ALLOW, h + ":" + port, "action reviewer"));
    }

    /** The start-up line for the reviewer, or null when it is off (spec 038). */
    public String describeReviewer() {
        if (reviewer == null) {
            return null;
        }
        var r = reviewer.settings();
        return String.format("Action reviewer: %s at %s · asks below %.2f, denies below %.2f%s", r.model(), r.url(), r.askBelow(), r.denyBelow(),
                NetworkRules.local(URI.create(r.url()).getHost()) ? "" : " · actions and their context are sent to this server");
    }

    /** {@code /mcp}: each server, how it is reached and its tools or error. */
    public List<String> describeMcp() {
        List<String> out = new java.util.ArrayList<>();
        if (mcp == null) {
            return out;
        }
        mcp.servers().values().forEach(srv -> {
            List<String> names = new java.util.ArrayList<>();
            srv.tools().forEach(t -> names.add(t.path("name").asText()));
            out.add(srv.name() + "  " + srv.kind() + "  " + (srv.error() != null ? "unavailable: " + srv.error() : String.join(", ", names)));
        });
        return out;
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

    /**
     * The slash commands (spec 035), loaded on first use; {@code builtIn} are the names the UI keeps for itself.
     */
    public synchronized CommandRunner commands(java.util.Set<String> builtIn) {
        if (commands == null) {
            commands = CommandRunner.load(home, workspace.root(), trusted, builtIn, settings, bus, turns, services);
        }
        return commands;
    }

    /** The permission mode now: {@code ask}, {@code accept-edits}, {@code plan} or {@code bypass} (spec 042). */
    public String permissionMode() {
        PermissionEngine e = engine;
        return e == null ? startMode : e.mode().name().toLowerCase().replace('_', '-');
    }

    /** Whether plan mode is on (spec 036). */
    public boolean planMode() {
        return plans.planMode();
    }

    /** {@code /plan}: turns plan mode on or off; returns the new state. */
    public boolean togglePlanMode() {
        return plans.toggle();
    }

    /** {@code /todos}: an agent's todo list as {@code [status, text]} pairs. */
    public List<String[]> todos(String agent) {
        List<String[]> out = new java.util.ArrayList<>();
        plans.list(agent).forEach(i -> out.add(new String[] {i.get("status").asText(), i.get("text").asText()}));
        return out;
    }

    /** The project's hook files, for the trust question (spec 034). */
    public static List<String> projectHooks(Path workspace) {
        return HookRunner.projectHooks(workspace);
    }

    /** {@code /hooks} (spec 034): each loaded hook as "name (source): functions", then the files that failed. */
    public List<String> describeHooks() {
        List<String> out = new java.util.ArrayList<>();
        for (HookRunner.Hook h : hooks.hooks()) {
            out.add(h.name() + "  " + h.source() + "  " + String.join(", ", h.functions()));
        }
        hooks.errors().forEach((f, e) -> out.add(f + "  broken: " + e));
        return out;
    }

    /** The session's tools (spec 028): the user's and, when used, the project's. */
    public ToolDefinitions tools() {
        return tools;
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
                i.steps(), i.durationMs(), i.open())).toList();
    }

    /** One subagent for {@code /agents}; {@code status} is {@code running} or how it ended. */
    public record Subagent(String name, String task, String customAgent, String model, boolean background, String status, int steps,
            long durationMs, boolean open) {}

    /** {@code /switch NAME} then a message (spec 032): a turn in that subagent's conversation. */
    public TurnResult talk(String name, String message, java.util.function.Consumer<String> onDelta) {
        return subagents.talk(name, message, onDelta);
    }

    /** Esc while talking to a subagent: stops its turn. */
    public void interruptSubagent(String name) {
        subagents.interrupt(name);
    }

    /** The subagent whose questions reach the user, or null (spec 032). */
    public void attend(String name) {
        subagents.attend(name);
    }

    /** The prompt of a turn that background subagents' replies start (spec 054); the replies come before it. */
    public static final String AUTO_PROMPT = "[No message from the user: this turn started because background subagents finished. "
            + "Their replies are above. Tell the user briefly what came back, and go on if the task needs more.]";

    /** Whether a background subagent finished and its reply hasn't reached the main agent yet (spec 054). */
    public boolean deliveryPending() {
        return subagents.deliveryPending();
    }

    /**
     * A main turn with the replies of the background subagents that finished (spec 054), or null when there are none.
     */
    public TurnResult deliveryTurn(java.util.function.Consumer<String> onDelta) {
        if (!subagents.deliveryPending()) {
            return null;
        }
        return loop.autoTurn(AUTO_PROMPT, onDelta);
    }

    /** Headless runs: waits until a background subagent's reply is ready (true) or none is running (false). */
    public boolean awaitFinished() throws InterruptedException {
        return subagents.awaitFinished();
    }

    /** Called on the subagent's thread when a background subagent finishes (spec 054); null for none. */
    public void onSubagentFinished(Runnable r) {
        subagents.onFinished(r);
    }

    /** Publishes a host event in the main agent's current turn (spec 055: the TUI's held questions). */
    public void publish(String type, com.fasterxml.jackson.databind.node.ObjectNode body) {
        bus.publish(turns.get(), type, body);
    }

    /** The background subagents holding a question for the user, oldest first. */
    public List<String> heldSubagents() {
        return subagents.held();
    }

    /**
     * {@code /answer NAME} (spec 054): asks that subagent's held question now and returns once it was answered; the
     * user stays in the main chat. False if it holds none.
     */
    public boolean answer(String name) {
        return subagents.answer(name);
    }

    /** Whether the user can switch to and talk to this subagent. */
    public boolean subagentOpen(String name) {
        return subagents.isOpen(name);
    }

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

    /** The files a prompt mentions with {@code @path}, read under the file policy (spec 040). */
    public Mentions.Result mentions(String prompt) {
        return Mentions.attach(prompt, files, Mentions.budget(loop.provider().contextWindow()));
    }

    /** Tab completion for an {@code @path} word (spec 040). */
    public java.util.List<String> completeMention(String word) {
        return Mentions.complete(word, files);
    }

    /** One user turn with its {@code @path} mentions attached (spec 040). */
    public TurnResult turn(String prompt, java.util.function.Consumer<String> onDelta) {
        return loop.turn(prompt, mentions(prompt), onDelta);
    }

    public Workspace workspace() {
        return workspace;
    }

    public Path logPath() {
        return log.path();
    }

    /** The saved session this one resumed or forked (spec 043), or null for a new one. */
    public SessionState.Resume resumed() {
        return resumed;
    }

    /** The home folder whose {@code .jharness/} this session uses. */
    public Path home() {
        return home;
    }

    public Settings settings() {
        return settings;
    }

    @Override
    public void close() throws IOException {
        subagents.closeAll();
        if (hooks != null) {
            hooks.close();
        }
        if (mcp != null) {
            mcp.close();
        }
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
