package dev.jharness.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import dev.jharness.agent.Session;
import dev.jharness.agent.TurnResult;
import dev.jharness.config.ConfigException;
import dev.jharness.config.Settings;
import dev.jharness.llm.ReplayProvider;
import dev.jharness.security.Prompter;
import dev.jharness.security.TrustStore;
import dev.jharness.process.ToolDefinitions;
import java.util.List;
import java.util.Map;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * The {@code jharness} command (AC-01, AC-02). Exit codes: 0 done, 1 turn error, 2 settings, usage or I/O
 * error, 3 internal error with a crash report (spec 021).
 */
@Command(name = "jharness", mixinStandardHelpOptions = true, version = "jharness " + Main.VERSION,
        description = "A terminal coding agent whose model acts by writing JavaScript in a sandbox.",
        subcommands = {LogCommand.class, ToolsCommand.class})
public final class Main implements Callable<Integer> {

    static final String VERSION = "0.1.0-SNAPSHOT";

    @Option(names = "--cwd", paramLabel = "DIR", description = "Workspace folder (default: the current folder).")
    Path cwd;

    @Option(names = "--model", paramLabel = "NAME", description = "Model profile from settings.json (default: \"model\").")
    String model;

    @Option(names = "--agent", paramLabel = "NAME", description = "Run as the custom agent NAME from .jharness/agents or ~/.jharness/agents.")
    String agentName;

    /** The custom agent, resolved once the project's trust is known. */
    private dev.jharness.agent.AgentDefinition agentDef;

    @Option(names = "--offline", description = "Offline mode: deny every connection no allow rule matches, run build tools offline, no git fetch, pull, push or clone.")
    boolean offline;

    @Option(names = {"-p", "--print"}, paramLabel = "PROMPT",
            description = "Run one prompt without the terminal UI and print the reply. Changes are denied, since no one can approve them.")
    String prompt;

    @Option(names = "--debug", description = "Show every event and the model's thinking as they happen; with -p, write events to stderr. Errors print their stack trace.")
    boolean debug;

    @Option(names = "--replay", hidden = true, paramLabel = "FILE", description = "Tests only: replay recorded model replies.")
    Path replay;

    @Option(names = "--home", hidden = true, paramLabel = "DIR", description = "Tests only: use DIR instead of the home folder.")
    Path home;

    PrintStream out = System.out;
    PrintStream err = System.err;
    /** Tests set a terminal on streams; null means the system terminal. */
    Terminal terminal;
    /** Tests only: runs once the session is open, before the first turn. */
    java.util.function.Consumer<Session> afterOpen;

    private volatile Session current;
    private volatile Path homeDir;
    /** The running command, for crashes on other threads. */
    private static volatile Main active;

    /** An internal error that already has its crash report. */
    static final class Crashed extends RuntimeException {
        final Path report;
        final Throwable crash;

        Crashed(Throwable crash, Path report) {
            super(crash);
            this.crash = crash;
            this.report = report;
        }
    }

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals(dev.jharness.process.ProxyBridge.FLAG)) {
            // Inside the Linux sandbox: forward the proxy port and run the build tool (D-049).
            System.exit(dev.jharness.process.ProxyBridge.run(java.util.Arrays.copyOfRange(args, 1, args.length)));
        }
        Thread.setDefaultUncaughtExceptionHandler(Main::uncaughtAnywhere);
        System.exit(run(new Main(), args));
    }

    private static void uncaughtAnywhere(Thread t, Throwable e) {
        Main m = active;
        (m != null ? m : new Main()).uncaught(t, e);
    }

    /** An exception on a thread other than the command's (AC-07): a report and a line; the session goes on. */
    void uncaught(Thread t, Throwable e) {
        Path report = report(e, t, current);
        err.println("jharness: internal error in thread " + t.getName() + " (" + CrashReport.summary(e) + ")"
                + (report != null ? "; crash report: " + report : ""));
        if (debug) {
            e.printStackTrace(err);
        }
    }

    /** Writes the report, then logs {@code session.crashed} if a session is open (AC-08, AC-09); null if it can't be written. */
    private Path report(Throwable e, Thread t, Session s) {
        Path h = homeDir != null ? homeDir : Path.of(System.getProperty("user.home"));
        CrashReport.Context c = s == null ? CrashReport.Context.NONE
                : new CrashReport.Context(s.bus().session(), s.logPath());
        Path report = null;
        try {
            report = CrashReport.write(h, e, t, c, java.time.Instant.now());
        } catch (IOException | RuntimeException x) {
            err.println("jharness: could not write a crash report: " + x.getMessage());
        }
        if (s != null) {
            try {
                s.bus().publish(dev.jharness.events.Origin.main(0), "session.crashed", dev.jharness.events.Json.object()
                        .put("exception", e.getClass().getName()).put("report", report == null ? "" : report.toString()));
            } catch (RuntimeException x) {
                // The session log may be what failed.
            }
        }
        return report;
    }

    /** Runs a session's work; an unexpected exception or error becomes a crash report (AC-07). */
    private <T> T guarded(Session s, java.util.concurrent.Callable<T> body) throws ConfigException, IOException {
        try {
            return body.call();
        } catch (ConfigException | IOException | java.io.UncheckedIOException | Crashed e) {
            throw e;
        } catch (RuntimeException | Error e) {
            throw new Crashed(e, report(e, Thread.currentThread(), s));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static int run(Main m, String... args) {
        // Model text, file text and exception messages reach the terminal escaped (spec 022).
        m.out = TerminalSafe.stream(m.out);
        m.err = TerminalSafe.stream(m.err);
        CommandLine cl = new CommandLine(m);
        // "jharness -p tools" is a prompt, not the tools command (spec 028).
        cl.setAllowSubcommandsAsOptionParameters(true);
        cl.setOut(new java.io.PrintWriter(m.out, true));
        cl.setErr(new java.io.PrintWriter(m.err, true));
        return cl.execute(args);
    }

    @Override
    public Integer call() {
        Path h = home != null ? home : Path.of(System.getProperty("user.home"));
        homeDir = h;
        active = this;
        Path ws = (cwd != null ? cwd : Path.of("")).toAbsolutePath().normalize();
        if (!java.nio.file.Files.isDirectory(ws)) {
            err.println("jharness: " + ws + " is not a folder");
            return 2;
        }
        try {
            TrustStore trust = TrustStore.load(h);
            if (prompt != null) {
                // Headless runs never ask: a folder not marked trusted is untrusted (D-043).
                boolean trusted = trust.trusted(ws).orElse(false);
                return headless(h, ws, layered(h, ws, trusted), trusted);
            }
            return interactive(h, ws, trust);
        } catch (ConfigException e) {
            err.println("jharness: " + e.getMessage());
            return 2;
        } catch (IOException | java.io.UncheckedIOException e) {
            err.println("jharness: " + e.getMessage());
            if (debug) {
                e.printStackTrace(err);
            }
            return 2;
        } catch (Crashed c) {
            return crashed(c.crash, c.report);
        } catch (RuntimeException | Error e) {
            return crashed(e, report(e, Thread.currentThread(), null));
        } finally {
            current = null;
        }
    }

    private int crashed(Throwable e, Path report) {
        err.println("jharness: internal error (" + CrashReport.summary(e) + ")" + (report != null ? "; crash report: " + report : ""));
        if (debug) {
            e.printStackTrace(err);
        } else {
            err.println("jharness: --debug prints the stack trace.");
        }
        return 3;
    }

    /** User, project and session layers; {@code --model} and {@code --offline} are the session layer (D-043). */
    private Settings.Layered layered(Path h, Path ws, boolean trusted) throws ConfigException {
        var session = dev.jharness.events.Json.object();
        agentDef = agentName == null ? null : dev.jharness.agent.AgentDefinition.resolve(h, ws, trusted, agentName);
        if (model != null) {
            session.put("model", model);
        } else if (agentDef != null && agentDef.model() != null) {
            // An agent's profile is used unless --model names another (D-032).
            session.put("model", agentDef.model());
        }
        if (offline) {
            session.put("offline", true);
        }
        return Settings.layered(h, ws, trusted, session);
    }

    private Session open(Path h, Path ws, Settings.Layered layered, Session.Access access) throws ConfigException, IOException {
        Session s;
        if (replay != null) {
            Path file = replay.toAbsolutePath();
            s = Session.open(h, ws, layered.settings(), access, (bus, turns) -> ReplayProvider.load(file, bus, turns));
        } else {
            s = Session.open(h, ws, layered.settings(), null, access);
        }
        current = s;
        if (!layered.ignored().isEmpty()) {
            var keys = dev.jharness.events.Json.array();
            layered.ignored().forEach(keys::add);
            var body = dev.jharness.events.Json.object();
            body.set("keys", keys);
            body.put("reason", "the project is not trusted");
            s.bus().publish(dev.jharness.events.Origin.main(0), "settings.ignored", body);
        }
        return s;
    }

    /** Records that a trusted project's tools were left out this session (D-066). */
    private static void projectIgnored(Session s, List<String> tools, String reason) {
        var body = dev.jharness.events.Json.object().put("reason", reason);
        var t = body.putArray("tools");
        tools.forEach(t::add);
        s.bus().publish(dev.jharness.events.Origin.main(0), "tools.project_ignored", body);
    }

    private int headless(Path h, Path ws, Settings.Layered settings, boolean trusted) throws ConfigException, IOException {
        // Headless runs can't ask: new or changed project tools are left out until confirmed in the UI (D-066).
        List<String> changed = trusted ? ToolDefinitions.changed(ToolDefinitions.projectHashes(ws), TrustStore.load(h).confirmedTools(ws)) : List.of();
        if (!changed.isEmpty()) {
            err.println("jharness: the project's tools " + String.join(", ", changed) + " are new or changed since you confirmed them;"
                    + " they are left out (start jharness in this folder to review them)");
        }
        try (Session s = open(h, ws, settings, Session.Access.prompter(Prompter.none()).trusted(trusted).agent(agentDef).projectTools(changed.isEmpty()))) {
            if (!changed.isEmpty()) {
                projectIgnored(s, changed, "headless runs can't ask about changed project tools");
            }
            s.bus().subscribe(e -> {
                if (e.type().equals("loop.result")) {
                    out.println();
                }
            });
            if (debug) {
                s.bus().subscribe(e -> {
                    if (!dev.jharness.events.SessionLog.LIVE_ONLY.contains(e.type())) {
                        err.println(TerminalSafe.escapeJson(e.toJsonLine()));
                    }
                });
            }
            TurnResult r = guarded(s, () -> {
                if (afterOpen != null) {
                    afterOpen.accept(s);
                }
                return s.loop().turn(prompt, d -> {
                    out.print(d);
                    out.flush();
                });
            });
            out.println();
            if (r.stopReason().equals("error")) {
                err.println("jharness: " + r.error());
                return 1;
            }
            if (!r.stopReason().equals("done")) {
                err.println("jharness: stopped: " + r.stopReason());
            }
            return 0;
        }
    }

    private int interactive(Path h, Path ws, TrustStore trust) throws ConfigException, IOException {
        Terminal t = terminal != null ? terminal : TerminalBuilder.builder().system(true).provider("ffm").build();
        try {
            Tui tui = new Tui(t, h.resolve(".jharness").resolve("history"));
            var saved = trust.trusted(ws);
            Map<String, String> hashes = ToolDefinitions.projectHashes(ws);
            boolean trusted = saved.orElseGet(() -> tui.askTrust(ws, ToolDefinitions.describeProject(h, ws), dev.jharness.agent.Session.projectHooks(ws)));
            boolean projectTools = true;
            if (saved.isEmpty()) {
                trust.record(ws, trusted, hashes);
            } else if (trusted) {
                Map<String, String> confirmed = trust.confirmedTools(ws);
                List<String> changed = ToolDefinitions.changed(hashes, confirmed);
                if (!changed.isEmpty()) {
                    projectTools = tui.askProjectTools(ToolDefinitions.describeProject(h, ws).stream().filter(p -> changed.contains(p.name())).toList());
                    if (projectTools) {
                        trust.confirmTools(ws, hashes);
                    }
                } else if (!hashes.equals(confirmed)) {
                    trust.confirmTools(ws, hashes);
                }
            }
            Settings.Layered settings = layered(h, ws, trusted);
            try (Session s = open(h, ws, settings, Session.Access.prompter(tui.prompter()).trusted(trusted).agent(agentDef).projectTools(projectTools).interactive(true))) {
                if (!projectTools) {
                    projectIgnored(s, ToolDefinitions.changed(hashes, trust.confirmedTools(ws)), "the user didn't confirm the changed project tools");
                }
                if (saved.isEmpty()) {
                    s.bus().publish(dev.jharness.events.Origin.main(0), "project.trusted", dev.jharness.events.Json.object().put("trusted", trusted));
                }
                if (debug) {
                    tui.debug();
                }
                guarded(s, () -> {
                    if (afterOpen != null) {
                        afterOpen.accept(s);
                    }
                    tui.run(s);
                    return null;
                });
            }
            return 0;
        } finally {
            if (terminal == null) {
                t.close();
            }
        }
    }
}
