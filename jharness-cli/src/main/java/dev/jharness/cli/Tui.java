package dev.jharness.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;

import dev.jharness.net.EgressProxy;
import dev.jharness.net.NetworkRules;
import dev.jharness.net.Rule;
import dev.jharness.net.RulesEditor;
import dev.jharness.net.RulesFile;
import dev.jharness.agent.Session;
import dev.jharness.agent.TurnResult;
import dev.jharness.config.ConfigException;
import dev.jharness.config.ModelProfile;
import dev.jharness.config.UiSettings;
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
              /events         show the event level and the session log file
              /events LEVEL   show none, steps or all events
              /thinking       show or hide the model's thinking
              /network        list the network rules, built-in denies and this session's allows
              /network allow|deny X [--project]   add a rule (host, *.suffix, IP or CIDR, optional :port)
              /network remove X [--project]       remove the rules naming X
              /network reload re-read both rules files
              Shift+Enter, Alt+Enter, Ctrl-J  new line; Enter sends
              Esc             stop the running turn
              /help           show this help
              /exit           quit (or Ctrl-D)
            """;

    /** xterm {@code modifyOtherKeys} level 1, so Shift+Enter is reported (D-051); and the reset. */
    static final String MODIFY_KEYS_ON = "\u001b[>4;1m";
    static final String MODIFY_KEYS_OFF = "\u001b[>4m";

    private final Terminal terminal;
    private final LineReader reader;
    private final PrintWriter out;
    private final Styler styler;
    private final StringBuilder pending = new StringBuilder();
    private TerminalPrompter prompter;
    private String events = UiSettings.defaults().events();
    private boolean showThinking;
    private boolean thinkingOpen;
    private boolean debug;

    Tui(Terminal terminal, Path historyFile) throws IOException {
        this(terminal, historyFile, System.getenv());
    }

    Tui(Terminal terminal, Path historyFile, Map<String, String> env) throws IOException {
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
        this.styler = Styler.forTerminal(terminal, env);
        if (!terminal.getType().startsWith("dumb")) {
            multiLine();
        }
    }

    /**
     * Alt+Enter and Ctrl-J insert a newline; Enter sends (spec 009 AC-01). Shift, Alt and Ctrl+Enter
     * also do, as {@code modifyOtherKeys} or CSI u sequences (spec 014 AC-02). Dumb terminals have no
     * raw keys, so they keep one line.
     */
    private void multiLine() {
        reader.getWidgets().put("jharness-newline", () -> {
            reader.getBuffer().write("\n");
            return true;
        });
        KeyMap<Binding> main = reader.getKeyMaps().get(LineReader.MAIN);
        main.bind(new Reference("jharness-newline"), KeyMap.alt('\r'));
        main.bind(new Reference("jharness-newline"), KeyMap.ctrl('J'));
        for (int mod = 2; mod <= 8; mod++) {
            main.bind(new Reference("jharness-newline"), "\u001b[27;" + mod + ";13~");
            main.bind(new Reference("jharness-newline"), "\u001b[13;" + mod + "u");
        }
        reader.setVariable(LineReader.SECONDARY_PROMPT_PATTERN, ". ");
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
            prompter = new TerminalPrompter(reader, styler);
        }
        return prompter;
    }

    /** {@code --debug} (spec 021 AC-01): start with every event and the thinking shown. */
    void debug() {
        debug = true;
    }

    void run(Session session) {
        events = debug ? "all" : session.settings().ui().events();
        showThinking = debug || session.settings().ui().thinking().equals("show");
        session.bus().subscribe(this::onEvent);
        out.println("JHarness in " + session.workspace().root() + " · model " + modelName(session)
                + (session.offline() ? " · offline" : "") + " · /help for commands");
        out.println(styler.dim("Session log: " + session.logPath()));
        out.flush();
        while (true) {
            String line;
            try {
                line = readPrompt();
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
                out.println(styler.error("[" + r.stopReason() + (r.error() == null ? "" : ": " + r.error()) + "]"));
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

    /** The {@code > } prompt, with modified keys reported only while it reads (spec 014 AC-01). */
    private String readPrompt() {
        boolean keys = !terminal.getType().startsWith("dumb");
        if (keys) {
            out.print(MODIFY_KEYS_ON);
            out.flush();
        }
        try {
            return reader.readLine("> ");
        } finally {
            if (keys) {
                out.print(MODIFY_KEYS_OFF);
                out.flush();
            }
        }
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
        styler.reset();
        try {
            return session.loop().turn(text, this::stream);
        } finally {
            flushPending();
            if (w != null) {
                prompter().watcher = null;
                w.close();
                terminal.setAttributes(saved);
            }
        }
    }

    /** {@code /network} (spec 011 AC-09): list, edit and reload the rules files. */
    private void network(Session session, String arg) {
        EgressProxy p = session.proxy();
        if (p == null) {
            out.println("This session has no egress proxy.");
            return;
        }
        List<String> a = arg.isEmpty() ? List.of() : List.of(arg.split("\\s+"));
        String sub = a.isEmpty() ? "list" : a.getFirst();
        boolean project = a.contains("--project");
        List<String> targets = a.stream().skip(1).filter(x -> !x.equals("--project")).toList();
        NetworkRules r = p.rules();
        switch (sub) {
            case "list" -> {
                listRules("General rules", r.general(), "");
                listRules("Project rules", r.project(), r.trusted() ? "" : " (project not trusted: only deny lines count)");
                out.println("Model endpoints, allowed automatically: "
                        + (r.automatic().isEmpty() ? "none" : String.join(", ", r.automatic().stream().map(x -> x.line().substring(6)).toList())));
                out.println("Built-in denies (unless an allow names the address): " + String.join(", ", NetworkRules.BUILT_IN_DENY));
                out.println("Allowed for this session: " + (p.sessionAllows().isEmpty() ? "none" : String.join(", ", p.sessionAllows())));
            }
            case "reload" -> {
                try {
                    p.reload();
                    out.println("Network rules reloaded.");
                } catch (ConfigException e) {
                    out.println(styler.error(e.getMessage()));
                }
            }
            case "allow", "deny", "remove" -> {
                if (targets.size() != 1) {
                    out.println("Usage: /network " + sub + " HOST|*.SUFFIX|IP|CIDR[:PORT] [--project]");
                    return;
                }
                if (project && !r.trusted()) {
                    out.println("This project isn't trusted, so its rules file can't be edited with --project; edit the general file instead.");
                    return;
                }
                Path file = project ? r.project().path() : r.general().path();
                try {
                    if (sub.equals("remove")) {
                        int n = RulesEditor.remove(file, targets.getFirst());
                        out.println(n == 0 ? "No rule names " + targets.getFirst() + " in " + file + "." : "Removed " + n + " line(s) from " + file + ".");
                    } else {
                        String line = RulesEditor.add(file, sub.equals("allow") ? Rule.Action.ALLOW : Rule.Action.DENY, targets.getFirst());
                        out.println("Added \"" + line + "\" to " + file + ".");
                    }
                    p.reload();
                } catch (IllegalArgumentException e) {
                    out.println(styler.error(e.getMessage()));
                } catch (IOException e) {
                    out.println(styler.error("cannot write " + file + ": " + e.getMessage()));
                } catch (ConfigException e) {
                    out.println(styler.error(e.getMessage()));
                }
            }
            default -> out.println("Usage: /network [list|allow X|deny X|remove X|reload] [--project]");
        }
    }

    private void listRules(String title, RulesFile f, String note) {
        out.println(title + ": " + f.path() + note);
        f.defaultAction().ifPresent(d -> out.println("  default " + d.word()));
        if (f.rules().isEmpty() && f.defaultAction().isEmpty()) {
            out.println("  (none)");
        }
        for (Rule x : f.rules()) {
            out.println("  " + x.line());
        }
    }

    /** Prints whole lines of the streamed reply, styled; the rest waits for its newline (AC-02). */
    private void stream(String delta) {
        pending.append(delta);
        int nl;
        boolean printed = false;
        while ((nl = pending.indexOf("\n")) >= 0) {
            out.println(styler.line(pending.substring(0, nl)));
            pending.delete(0, nl + 1);
            printed = true;
        }
        if (printed) {
            out.flush();
        }
    }

    /** Ends the thinking text with a blank line before whatever comes next. */
    private void closeThinking() {
        if (thinkingOpen) {
            thinkingOpen = false;
            out.println();
            out.println();
            out.flush();
        }
    }

    private void flushPending() {
        if (!pending.isEmpty()) {
            out.print(styler.line(pending.toString()));
            pending.setLength(0);
            out.flush();
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
            case "/network" -> network(session, parts.length > 1 ? parts[1].strip() : "");
            case "/thinking" -> {
                showThinking = !showThinking;
                out.println("Thinking: " + (showThinking ? "shown" : "hidden"));
            }
            case "/events" -> {
                if (parts.length == 1) {
                    out.println("Events: " + events + " (none, steps or all). Session log: " + session.logPath());
                } else if (UiSettings.EVENT_LEVELS.contains(parts[1].strip())) {
                    events = parts[1].strip();
                    out.println("Events: " + events);
                } else {
                    out.println("Usage: /events [none|steps|all]");
                }
            }
            default -> out.println("Unknown command " + parts[0] + ". Type /help for the commands.");
        }
        out.flush();
        return true;
    }

    /** Package-private for tests. */
    void onEvent(Event e) {
        if (e.type().equals("model.thinking_delta")) {
            if (showThinking) {
                if (!thinkingOpen) {
                    out.println();
                    thinkingOpen = true;
                }
                out.print(styler.thinking(e.body().get("text").asText()));
                out.flush();
            }
            return;
        }
        closeThinking();
        if (e.type().equals("model.delta")) {
            return;
        }
        flushPending();
        boolean steps = !events.equals("none");
        switch (e.type()) {
            case "loop.code" -> {
                if (steps) {
                    out.println();
                    out.println(styler.step("▸ step " + e.body().get("step").asInt() + ": running " + e.body().get("lines").asInt() + " lines"));
                }
            }
            case "file.changed" -> {
                String path = e.body().get("path").asText();
                String diff = e.body().get("diff").asText();
                TerminalPrompter p = prompter;
                if (p != null && path.equals(p.shownPath)) {
                    p.shownPath = null;
                } else if (!diff.isEmpty()) {
                    out.println();
                    out.print(styler.diff(diff));
                }
            }
            case "loop.result" -> {
                String status = e.body().get("status").asText();
                String limit = e.body().has("limit") ? " (" + e.body().get("limit").asText() + ")" : "";
                if (steps) {
                    String text = "▸ step " + e.body().get("step").asInt() + ": " + status + limit;
                    out.println(status.equals("ok") ? styler.step(text) : styler.error(text));
                }
                if (e.body().has("error")) {
                    int line = e.body().path("line").asInt(0);
                    out.println(styler.error("  " + (line > 0 ? "line " + line + ": " : "") + e.body().get("error").asText()));
                }
            }
            case "model.retry" -> {
                if (steps) {
                    long s = (e.body().get("waitMs").asLong() + 999) / 1000;
                    out.println(styler.dim("▸ " + e.body().get("reason").asText() + " from " + e.body().get("provider").asText()
                            + ", retrying in " + s + " s (attempt " + e.body().get("attempt").asInt() + " of "
                            + e.body().get("attempts").asInt() + ")"));
                }
            }
            default -> {
                // Other events go to the session log only, unless all events are shown.
            }
        }
        if (events.equals("all")) {
            out.println(styler.dim("· " + e.type() + " " + e.body()));
        }
        out.flush();
    }

    Terminal terminal() {
        return terminal;
    }
}
