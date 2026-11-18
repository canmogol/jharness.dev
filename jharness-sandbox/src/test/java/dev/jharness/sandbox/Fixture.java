package dev.jharness.sandbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.jharness.config.SandboxSettings;
import dev.jharness.config.Timeouts;
import dev.jharness.events.Event;
import dev.jharness.events.EventBus;
import dev.jharness.events.Origin;
import dev.jharness.process.Executable;
import dev.jharness.process.Executables;
import dev.jharness.process.GitOperations;
import dev.jharness.process.ProcessRunner;
import dev.jharness.process.Search;
import dev.jharness.security.PermissionGate;
import dev.jharness.security.Workspace;

/** A workspace with a git repository, an event bus that records events, and api services. */
final class Fixture {

    final Path root;
    final Workspace ws;
    final EventBus bus = new EventBus("s-sandbox");
    final List<Event> events = new java.util.concurrent.CopyOnWriteArrayList<>();
    final GitOperations git;
    final Search search;

    Fixture(Path tmp) throws Exception {
        root = Files.createDirectories(tmp.resolve("ws"));
        bus.subscribe(events::add);
        Executable exe = Executables.resolve("git", root).orElseThrow();
        for (List<String> c : List.of(List.of("init", "-q", "-b", "main"), List.of("config", "user.email", "t@example.invalid"),
                List.of("config", "user.name", "T"))) {
            List<String> cmd = new ArrayList<>(List.of(exe.path().toString()));
            cmd.addAll(c);
            Process p = new ProcessBuilder(cmd).directory(root.toFile()).start();
            if (p.waitFor() != 0) {
                throw new IllegalStateException("git setup failed");
            }
        }
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/App.java"), "class App {\n  // TODO fix the bug\n  int answer() { return 41; }\n}\n");
        ws = new Workspace(root);
        ProcessRunner runner = new ProcessRunner(bus, () -> Origin.main(1), 30_000);
        git = new GitOperations(runner, exe, ws, Duration.ofSeconds(30));
        search = Search.create(ws, bus, () -> Origin.main(1), Timeouts.defaults());
    }

    ApiServices services(PermissionGate gate) {
        return new ApiServices(ws, gate, bus, () -> Origin.main(1), search, git);
    }

    static SandboxSettings.Profile profile(Map<String, String> changes) throws Exception {
        return SandboxSettings.defaults().profile("main").with(changes);
    }

    static AgentIsolate isolate(SandboxSettings.Profile p, EventBus bus) {
        return new AgentIsolate("main", p, bus, () -> Origin.main(1), IsolateManager.newEngine(p, false), false);
    }

    List<String> types() {
        return events.stream().map(Event::type).toList();
    }
}
