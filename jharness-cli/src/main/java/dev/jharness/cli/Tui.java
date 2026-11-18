package dev.jharness.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;

import dev.jharness.agent.Session;
import dev.jharness.agent.TurnResult;
import dev.jharness.config.ConfigException;
import dev.jharness.config.ModelProfile;
import dev.jharness.events.Event;

/** The terminal UI (AC-03, AC-05): line editing and history, streamed replies, code steps, slash commands. */
final class Tui {

    static final String HELP = """
            Commands:
              /model          list model profiles
              /model NAME     switch to another profile
              /diff           show the last turn's file changes
              /undo           undo the last turn's file changes and remove it from the conversation
              /rewind N       undo every turn after turn N (add --force to overwrite your own edits)
              Esc             stop the running turn
              /help           show this help
              /exit           quit (or Ctrl-D)
            """;

    private final Terminal terminal;
    private final LineReader reader;
    private final PrintWriter out;
    private TerminalPrompter prompter;

    Tui(Terminal terminal, Path historyFile) throws IOException {
        this.terminal = terminal;
        if (historyFile != null) {
            Files.createDirectories(historyFile.getParent());
            if (!Files.exists(historyFile)) {
                Files.createFile(historyFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            }
            Files.setPosixFilePermissions(historyFile, PosixFilePermissions.fromString("rw-------"));
        }
        LineReaderBuilder b = LineReaderBuilder.builder().terminal(terminal).appName("jharness");
        if (historyFile != null) {
            b.variable(LineReader.HISTORY_FILE, historyFile);
        }
        this.reader = b.build();
        this.out = terminal.writer();
    }

    /** The trust question (AC-01, AC-02); end of input means no. */
    boolean askTrust(Path workspace) {
        out.println("Do you trust " + workspace + "?");
        out.println("Trusting it lets its .jharness/settings.json loosen JHarness rules (permission mode, sandbox limits, mounts, models).");
        out.println("Untrusted projects can only make JHarness stricter.");
        out.flush();
        while (true) {
            String a;
            try {
                a = reader.readLine("[y] trust  [n] don't trust: ");
            } catch (UserInterruptException | EndOfFileException e) {
                return false;
            }
            switch (a.strip().toLowerCase()) {
                case "y", "yes" -> {
                    return true;
                }
                case "n", "no" -> {
                    return false;
                }
                default -> {
                    out.println("Please answer y or n.");
                    out.flush();
                }
            }
        }
    }

    TerminalPrompter prompter() {
        if (prompter == null) {
            prompter = new TerminalPrompter(reader);
        }
        return prompter;
    }

    void run(Session session) {
        session.bus().subscribe(this::onEvent);
        out.println("JHarness in " + session.workspace().root() + " · model " + modelName(session) + " · /help for commands");
        out.flush();
        while (true) {
            String line;
            try {
                line = reader.readLine("> ");
            } catch (UserInterruptException e) {
                continue;
            } catch (EndOfFileException e) {
                break;
            }
            String t = line.strip();
            if (t.isEmpty()) {
                continue;
            }
            if (t.startsWith("/")) {
                if (!command(session, t)) {
                    break;
                }
                continue;
            }
            TurnResult r = turn(session, t);
            out.println();
            if (!r.stopReason().equals("done")) {
                out.println("[" + r.stopReason() + (r.error() == null ? "" : ": " + r.error()) + "]");
            }
            out.flush();
        }
        try {
            reader.getHistory().save();
        } catch (IOException e) {
            out.println("could not save history: " + e.getMessage());
        }
        out.flush();
    }

    /** Runs a turn with Esc watched (not on dumb terminals, which have no raw input). */
    private TurnResult turn(Session session, String text) {
        boolean watch = !terminal.getType().startsWith("dumb");
        Attributes saved = null;
        EscWatcher w = null;
        if (watch) {
            saved = terminal.getAttributes();
            Attributes a = new Attributes(saved);
            a.setLocalFlag(Attributes.LocalFlag.ICANON, false);
            a.setLocalFlag(Attributes.LocalFlag.ECHO, false);
            a.setControlChar(Attributes.ControlChar.VMIN, 0);
            a.setControlChar(Attributes.ControlChar.VTIME, 1);
            terminal.setAttributes(a);
            w = new EscWatcher(terminal.reader(), session.loop()::interrupt);
            prompter().watcher = w;
        }
        try {
            return session.loop().turn(text, d -> {
                out.print(d);
                out.flush();
            });
        } finally {
            if (w != null) {
                prompter().watcher = null;
                w.close();
                terminal.setAttributes(saved);
            }
        }
    }

    private static String modelName(Session s) {
        return s.profile() != null ? s.profile() : s.loop().provider().provider();
    }

    /** @return false to quit */
    boolean command(Session session, String line) {
        String[] parts = line.split("\\s+", 2);
        switch (parts[0]) {
            case "/exit", "/quit" -> {
                return false;
            }
            case "/help" -> out.print(HELP);
            case "/diff" -> {
                String d = session.loop().lastTurnDiff();
                out.print(d.isEmpty() ? "No file changes in the last turn.\n" : d);
            }
            case "/undo" -> out.println(session.loop().undo(line.contains("--force")).message());
            case "/rewind" -> {
                String[] a = line.split("\\s+");
                if (a.length < 2 || !a[1].matches("[0-9]+")) {
                    out.println("Usage: /rewind N [--force]. Turns: " + session.loop().turns());
                } else {
                    out.println(session.loop().rewind(Integer.parseInt(a[1]), line.contains("--force")).message());
                }
            }
            case "/model" -> {
                if (parts.length == 1) {
                    if (session.models().all().isEmpty()) {
                        out.println("No model profiles in settings.json.");
                    }
                    for (Map.Entry<String, ModelProfile> e : session.models().all().entrySet()) {
                        ModelProfile p = e.getValue();
                        out.println((e.getKey().equals(session.profile()) ? "* " : "  ") + e.getKey() + "  " + p.provider() + "  " + p.model());
                    }
                } else {
                    try {
                        session.switchModel(parts[1].strip());
                        out.println("Model: " + session.profile());
                    } catch (ConfigException e) {
                        out.println(e.getMessage());
                    }
                }
            }
            default -> out.println("Unknown command " + parts[0] + ". Type /help for the commands.");
        }
        out.flush();
        return true;
    }

    private void onEvent(Event e) {
        switch (e.type()) {
            case "loop.code" -> {
                out.println();
                out.println("▸ step " + e.body().get("step").asInt() + ": running " + e.body().get("lines").asInt() + " lines");
                out.flush();
            }
            case "file.changed" -> {
                String path = e.body().get("path").asText();
                String diff = e.body().get("diff").asText();
                TerminalPrompter p = prompter;
                if (p != null && path.equals(p.shownPath)) {
                    p.shownPath = null;
                } else if (!diff.isEmpty()) {
                    out.println();
                    out.print(diff);
                    out.flush();
                }
            }
            case "loop.result" -> {
                String limit = e.body().has("limit") ? " (" + e.body().get("limit").asText() + ")" : "";
                out.println("▸ step " + e.body().get("step").asInt() + ": " + e.body().get("status").asText() + limit);
                out.flush();
            }
            default -> {
                // Other events go to the session log only.
            }
        }
    }

    Terminal terminal() {
        return terminal;
    }
}
