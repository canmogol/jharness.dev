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

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** The {@code jharness} command (AC-01, AC-02). Exit codes: 0 done, 1 turn error, 2 settings or usage error. */
@Command(name = "jharness", mixinStandardHelpOptions = true, version = "jharness " + Main.VERSION,
        description = "A terminal coding agent whose model acts by writing JavaScript in a sandbox.")
public final class Main implements Callable<Integer> {

    static final String VERSION = "0.1.0-SNAPSHOT";

    @Option(names = "--cwd", paramLabel = "DIR", description = "Workspace folder (default: the current folder).")
    Path cwd;

    @Option(names = "--model", paramLabel = "NAME", description = "Model profile from settings.json (default: \"model\").")
    String model;

    @Option(names = {"-p", "--print"}, paramLabel = "PROMPT",
            description = "Run one prompt without the terminal UI and print the reply. Changes are denied, since no one can approve them.")
    String prompt;

    @Option(names = "--replay", hidden = true, paramLabel = "FILE", description = "Tests only: replay recorded model replies.")
    Path replay;

    @Option(names = "--home", hidden = true, paramLabel = "DIR", description = "Tests only: use DIR instead of the home folder.")
    Path home;

    PrintStream out = System.out;
    PrintStream err = System.err;
    /** Tests set a terminal on streams; null means the system terminal. */
    Terminal terminal;

    public static void main(String[] args) {
        System.exit(run(new Main(), args));
    }

    static int run(Main m, String... args) {
        CommandLine cl = new CommandLine(m);
        cl.setOut(new java.io.PrintWriter(m.out, true));
        cl.setErr(new java.io.PrintWriter(m.err, true));
        return cl.execute(args);
    }

    @Override
    public Integer call() {
        Path h = home != null ? home : Path.of(System.getProperty("user.home"));
        Path ws = (cwd != null ? cwd : Path.of("")).toAbsolutePath().normalize();
        Settings settings;
        try {
            settings = Settings.load(h);
        } catch (ConfigException e) {
            err.println("jharness: " + e.getMessage());
            return 2;
        }
        try {
            if (prompt != null) {
                return headless(h, ws, settings);
            }
            return interactive(h, ws, settings);
        } catch (ConfigException e) {
            err.println("jharness: " + e.getMessage());
            return 2;
        } catch (IOException | RuntimeException e) {
            err.println("jharness: " + e.getMessage());
            return 2;
        }
    }

    private Session open(Path h, Path ws, Settings settings, Session.Access access) throws ConfigException, IOException {
        if (replay != null) {
            Path file = replay.toAbsolutePath();
            return Session.open(h, ws, settings, access, (bus, turns) -> ReplayProvider.load(file, bus, turns));
        }
        return Session.open(h, ws, settings, model, access);
    }

    private int headless(Path h, Path ws, Settings settings) throws ConfigException, IOException {
        try (Session s = open(h, ws, settings, Session.Access.prompter(Prompter.none()))) {
            s.bus().subscribe(e -> {
                if (e.type().equals("loop.result")) {
                    out.println();
                }
            });
            TurnResult r = s.loop().turn(prompt, d -> {
                out.print(d);
                out.flush();
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

    private int interactive(Path h, Path ws, Settings settings) throws ConfigException, IOException {
        Terminal t = terminal != null ? terminal : TerminalBuilder.builder().system(true).provider("ffm").build();
        try {
            Tui tui = new Tui(t, h.resolve(".jharness").resolve("history"));
            try (Session s = open(h, ws, settings, Session.Access.prompter(tui.prompter()))) {
                tui.run(s);
            }
            return 0;
        } finally {
            if (terminal == null) {
                t.close();
            }
        }
    }
}
