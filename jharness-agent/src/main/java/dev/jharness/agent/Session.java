package dev.jharness.agent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import dev.jharness.config.ConfigException;
import dev.jharness.config.ModelProfile;
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

    private Session(EventBus bus, SessionLog log, AgentIsolate isolate, AgentLoop loop, Workspace workspace) {
        this.bus = bus;
        this.log = log;
        this.isolate = isolate;
        this.loop = loop;
        this.workspace = workspace;
    }

    /**
     * How the session decides permissions: the permission engine from settings with a prompter (the
     * normal case), or a fixed gate (tests).
     */
    public record Access(PermissionGate gate, Prompter prompter) {
        public static Access prompter(Prompter p) {
            return new Access(null, p);
        }

        public static Access gate(PermissionGate g) {
            return new Access(g, null);
        }
    }

    /** Opens a session with the provider for {@code profileName} (null = the default profile). */
    public static Session open(Path home, Path workspace, Settings settings, String profileName, Access access)
            throws ConfigException, IOException {
        ModelProfile p = settings.models().resolve(profileName);
        return open(home, workspace, settings, access, (bus, turns) -> ModelProviders.create(p, bus, turns));
    }

    /** Builds the provider once the bus exists; tests pass a replay provider. */
    public interface ProviderFactory {
        ModelProvider create(EventBus bus, TurnCounter turns) throws IOException;
    }

    public static Session open(Path home, Path workspacePath, Settings settings, Access access, ProviderFactory providers)
            throws IOException {
        Workspace ws = new Workspace(workspacePath);
        EventBus bus = new EventBus(EventBus.newSessionId());
        SessionLog log = SessionLog.open(home, ws.root(), bus.session());
        bus.subscribe(log);
        TurnCounter turns = new TurnCounter("main");
        ModelProvider provider = providers.create(bus, turns);
        bus.publish(turns.get(), "session.started", Json.object().put("workspace", ws.root().toString())
                .put("provider", provider.provider()).put("model", provider.model()));
        ProcessRunner runner = new ProcessRunner(bus, turns, settings.timeouts().outputChars());
        Optional<Executable> git = Executables.resolve("git", ws.root());
        GitOperations gitOps = git.map(g -> new GitOperations(runner, g, ws, settings.timeouts().process())).orElse(null);
        FilePolicy files = new FilePolicy(ws, settings.files().mounts(), settings.files().deny());
        Search search = Search.create(ws, bus, turns, settings.timeouts(), files.denyList());
        PermissionGate gate = access.gate() != null ? access.gate()
                : new PermissionEngine(PermissionMode.of(settings.permissions().mode()), ProjectRules.load(home, ws.root()),
                        access.prompter(), bus, turns);
        ApiServices services = new ApiServices(files, gate, bus, turns, search, gitOps);
        AgentIsolate isolate = new IsolateManager(settings.sandbox(), bus, turns).create("main", "main");
        RunResult ctx = isolate.run("api.readContext()", services);
        String system = SystemPrompt.build(ctx.ok() ? ctx.value() : null);
        AgentLoop loop = new AgentLoop(provider, isolate, services, bus, turns, system, settings.agent().maxSteps(),
                settings.timeouts().outputChars());
        return new Session(bus, log, isolate, loop, ws);
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

    @Override
    public void close() throws IOException {
        isolate.close();
        log.close();
    }
}
