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
              /agent          list custom agents (start one with jharness --agent NAME)
              /agents         list this session's subagents
              /agents NAME    show a subagent's last 20 events
              /follow [NAME]  watch a background subagent live, like the main loop (Esc stops watching)
              /stop NAME|all  stop a running background subagent, or all of them
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
              /help           show this help (or type / and press Tab)
              /exit           quit (or Ctrl-D)
            """;

    /** Each command in {@link #HELP} and its first description line, for Tab completion (spec 024). */
    static final java.util.SequencedMap<String, String> COMMANDS = commands();

    private static java.util.SequencedMap<String, String> commands() {
        var m = new java.util.LinkedHashMap<String, String>();
        var p = java.util.regex.Pattern.compile("^ {2}(/[a-z]+)\\S*(?: \\S+)*?\\s{2,}(\\S.*)$");
        for (String line : HELP.lines().toList()) {
            var x = p.matcher(line);
            if (x.matches()) {
                m.putIfAbsent(x.group(1), x.group(2));
            }
        }
        return java.util.Collections.unmodifiableSequencedMap(m);
    }

    /** xterm {@code modifyOtherKeys} level 1, so Shift+Enter is reported (D-051); and the reset. */
    static final String MODIFY_KEYS_ON = "\u001b[>4;1m";
    static final String MODIFY_KEYS_OFF = "\u001b[>4m";

    private final Terminal terminal;
    private final LineReader reader;
    /**
     * Everything printed goes through {@code out}, which escapes control characters (spec 022). Only
     * {@link Styler} output, which escapes its own input, and fixed key-mode sequences use {@code raw}.
     */
    private final PrintWriter out;
    private final PrintWriter raw;
    private final Styler styler;
    private final StringBuilder pending = new StringBuilder();
    private TerminalPrompter prompter;
    private String events = UiSettings.defaults().events();
    private boolean showThinking;
    private boolean thinkingOpen;
    private boolean debug;
    /** Background subagents, by name: only their start and end lines are shown (spec 027). */
    private final java.util.Set<String> background = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private Path logPath;
    /** The background subagent {@code /follow} shows, or null (spec 030). */
    private volatile String following;

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
        LineReaderBuilder b = LineReaderBuilder.builder().terminal(terminal).appName("jharness").completer(Tui::completeCommand);
        if (historyFile != null) {
            b.variable(LineReader.HISTORY_FILE, historyFile);
        }
        this.reader = b.build();
        this.raw = terminal.writer();
        this.out = new PrintWriter(new TerminalSafe.SafeWriter(raw), false);
        this.styler = Styler.forTerminal(terminal, env);
        if (!terminal.getType().startsWith("dumb")) {
            multiLine();
        }
        slashTab();
    }

    /** Command names for a first word that starts with {@code /} (spec 024 AC-02); nothing otherwise. */
    static void completeCommand(LineReader r, org.jline.reader.ParsedLine line, List<org.jline.reader.Candidate> out) {
        if (line.wordIndex() != 0 || !line.word().startsWith("/")) {
            return;
        }
        COMMANDS.forEach((name, desc) -> out.add(new org.jline.reader.Candidate(name, name, null, desc, null, null, true)));
    }

    /** Tab on a lone {@code /} prints the help above the prompt (spec 024 AC-01); otherwise it completes as usual. */
    private void slashTab() {
        var complete = reader.getWidgets().get(LineReader.COMPLETE_WORD);
        reader.getWidgets().put("jharness-tab", () -> {
            if (reader.getBuffer().toString().strip().equals("/")) {
                reader.printAbove(HELP.stripTrailing());
                return true;
            }
            return complete.apply();
        });
        reader.getKeyMaps().get(LineReader.MAIN).bind(new Reference("jharness-tab"), KeyMap.ctrl('I'));
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
        return askTrust(workspace, List.of());
    }

    /** The trust question, listing the tools the project defines (spec 028, D-066). */
    boolean askTrust(Path workspace, List<dev.jharness.process.ToolDefinitions.ProjectTool> tools) {
        out.println("Do you trust " + workspace + "?");
        out.println("Trusting it lets its .jharness/settings.json loosen JHarness rules (permission mode, sandbox limits, mounts, models).");
        if (!tools.isEmpty()) {
            out.println("It also lets JHarness run the tools the project defines in .jharness/tools/:");
            tools.forEach(t -> out.println("  " + t.line()));
        }
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

    /**
     * A trusted project's tool files are new or changed since the user confirmed them (D-066).
     *
     * @return true to use the project's tools this session
     */
    boolean askProjectTools(List<dev.jharness.process.ToolDefinitions.ProjectTool> changed) {
        out.println("This project's tools are new or changed since you last confirmed them (.jharness/tools/):");
        changed.forEach(t -> out.println("  " + t.line()));
        out.flush();
        while (true) {
            String a;
            try {
                a = reader.readLine("Use the project's tools this session? [y] yes  [n] no, only mine: ");
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
            prompter = new TerminalPrompter(reader, styler, out, raw);
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
        logPath = session.logPath();
        out.println("JHarness in " + session.workspace().root() + " · model " + modelName(session)
                + (session.agent() != null ? " · agent " + session.agent().name() : "")
                + (session.offline() ? " · offline" : "") + " · /help for commands");
        raw.println(styler.dim("Session log: " + session.logPath()));
        var project = session.tools().project();
        if (!project.isEmpty()) {
            out.println("Project tools: " + String.join("; ", project.stream().map(dev.jharness.process.ToolDefinitions.ProjectTool::line).toList()));
        }
        session.tools().errors().forEach((n, err) -> raw.println(styler.error("Tool " + n + " is unavailable: " + err)));
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
                raw.println(styler.error("[" + r.stopReason() + (r.error() == null ? "" : ": " + r.error()) + "]"));
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
            raw.print(MODIFY_KEYS_ON);
            out.flush();
        }
        try {
            return reader.readLine("> ");
        } finally {
            if (keys) {
                raw.print(MODIFY_KEYS_OFF);
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
        prompter().idle = false;
        try {
            return session.loop().turn(text, this::stream);
        } finally {
            prompter().idle = true;
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
                    raw.println(styler.error(e.getMessage()));
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
                    raw.println(styler.error(e.getMessage()));
                } catch (IOException e) {
                    raw.println(styler.error("cannot write " + file + ": " + e.getMessage()));
                } catch (ConfigException e) {
                    raw.println(styler.error(e.getMessage()));
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
            raw.println(styler.line(pending.substring(0, nl)));
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
            raw.print(styler.line(pending.toString()));
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
            case "/undo" -> {
                if (!busy(session, "/undo")) {
                    out.println(session.loop().undo(line.contains("--force")).message());
                }
            }
            case "/rewind" -> {
                String[] a = line.split("\\s+");
                if (busy(session, "/rewind")) {
                    break;
                }
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
            case "/agent" -> {
                session.describeAgents().forEach(out::println);
                out.println("Agents live in .jharness/agents/NAME/ or ~/.jharness/agents/NAME/; start one with jharness --agent NAME.");
            }
            case "/agents" -> agents(session, parts.length > 1 ? parts[1].strip() : "");
            case "/stop" -> stop(session, parts.length > 1 ? parts[1].strip() : "");
            case "/follow" -> follow(session, parts.length > 1 ? parts[1].strip() : "");
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

    /** Undo would race a running background subagent's writes (spec 027 AC-06). */
    private boolean busy(Session session, String what) {
        List<String> running = session.runningSubagents();
        if (running.isEmpty()) {
            return false;
        }
        out.println(what + " waits for the background subagents to end: " + String.join(", ", running)
                + " still running. Stop them with /stop all.");
        return true;
    }

    /** {@code /agents} and {@code /agents NAME} (spec 027 AC-08). */
    private void agents(Session session, String name) {
        List<Session.Subagent> all = session.subagents();
        if (name.isEmpty()) {
            if (all.isEmpty()) {
                out.println("No subagents in this session.");
                return;
            }
            String fmt = "%-8s  %-11s  %5s  %-12s  %-12s  %6s  %s%n";
            out.printf(fmt, "NAME", "STATUS", "STEPS", "AGENT", "MODEL", "TIME", "TASK");
            for (Session.Subagent a : all) {
                String task = a.task().strip().replaceAll("\\s+", " ");
                out.printf(fmt, a.name(), a.status() + (a.background() ? "" : "*"), a.steps(), LogFormat.cut(a.customAgent(), 12),
                        LogFormat.cut(a.model(), 12), (a.durationMs() / 1000) + "s", LogFormat.cut(task, 60));
            }
            out.println("* ran in the foreground. /agents NAME shows one's events, /follow NAME watches one live, /stop NAME stops one.");
            return;
        }
        if (all.stream().noneMatch(a -> a.name().equals(name))) {
            out.println("No subagent " + name + " in this session." + (all.isEmpty() ? "" : " Subagents: "
                    + String.join(", ", all.stream().map(Session.Subagent::name).toList()) + "."));
            return;
        }
        List<com.fasterxml.jackson.databind.JsonNode> mine = new java.util.ArrayList<>();
        try {
            for (LogCommand.Line l : LogCommand.read(logPath)) {
                if (l.event() != null && l.event().path("agent").asText().equals(name)) {
                    mine.add(l.event());
                }
            }
        } catch (IOException ex) {
            raw.println(styler.error("cannot read " + logPath + ": " + ex.getMessage()));
            return;
        }
        LogFormat f = new LogFormat(java.time.ZoneId.systemDefault(), false);
        int from = Math.max(0, mine.size() - 20);
        out.println("Last " + (mine.size() - from) + " of " + mine.size() + " events of " + name + " (jharness log --agent " + name + " shows all):");
        for (var ev : mine.subList(from, mine.size())) {
            out.println(f.line(ev));
        }
    }

    /**
     * {@code /follow [NAME]} (spec 030): shows a running background subagent's reply, steps and diffs as
     * they happen, until Esc or until it ends. The subagent keeps running either way.
     */
    private void follow(Session session, String arg) {
        List<String> running = session.runningSubagents();
        String name = arg.isEmpty() && running.size() == 1 ? running.getFirst() : arg;
        if (name.isEmpty()) {
            out.println(running.isEmpty() ? "No background subagent is running." : "Running: " + String.join(", ", running) + ". Use /follow NAME.");
            return;
        }
        if (!running.contains(name)) {
            boolean known = session.subagents().stream().anyMatch(a -> a.name().equals(name));
            out.println(known ? name + " isn't running; /agents " + name + " shows its events."
                    : "No subagent " + name + " in this session." + (running.isEmpty() ? "" : " Running: " + String.join(", ", running) + "."));
            return;
        }
        Session.Subagent a = session.subagents().stream().filter(x -> x.name().equals(name)).findFirst().orElseThrow();
        String task = a.task().strip().replaceAll("\\s+", " ");
        boolean keys = !terminal.getType().startsWith("dumb");
        out.println("Following " + name + " (" + (task.length() > 80 ? task.substring(0, 80) + "…" : task) + ", " + a.steps() + " steps so far)."
                + (keys ? " Esc stops following; " + name + " keeps running." : ""));
        out.flush();
        java.util.concurrent.CountDownLatch esc = new java.util.concurrent.CountDownLatch(1);
        Attributes saved = null;
        EscWatcher w = null;
        if (keys) {
            saved = terminal.getAttributes();
            Attributes r = new Attributes(saved);
            r.setLocalFlag(Attributes.LocalFlag.ICANON, false);
            r.setLocalFlag(Attributes.LocalFlag.ECHO, false);
            r.setControlChar(Attributes.ControlChar.VMIN, 0);
            r.setControlChar(Attributes.ControlChar.VTIME, 1);
            terminal.setAttributes(r);
            w = new EscWatcher(terminal.reader(), esc::countDown);
        }
        styler.reset();
        following = name;
        boolean stopped = false;
        try {
            while (session.runningSubagents().contains(name)) {
                if (esc.await(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    stopped = true;
                    break;
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (this) {
                following = null;
                flushPending();
                closeThinking();
            }
            if (w != null) {
                w.close();
                terminal.setAttributes(saved);
            }
        }
        out.println();
        out.println(stopped ? "Stopped following " + name + "; it keeps running (/follow " + name + " to watch again)."
                : name + " has ended; /agents " + name + " shows its events.");
    }

    /** {@code /stop NAME} and {@code /stop all} (spec 027 AC-08). */
    private void stop(Session session, String name) {
        if (name.isEmpty()) {
            out.println("Usage: /stop NAME|all. Running: " + (session.runningSubagents().isEmpty() ? "none" : String.join(", ", session.runningSubagents())));
        } else if (name.equals("all")) {
            List<String> stopped = session.stopSubagents();
            out.println(stopped.isEmpty() ? "No subagents are running." : "Stopped " + String.join(", ", stopped) + ".");
        } else if (session.stopSubagent(name)) {
            out.println("Stopped " + name + ".");
        } else {
            out.println(name + " isn't running. Running: " + (session.runningSubagents().isEmpty() ? "none" : String.join(", ", session.runningSubagents())));
        }
    }

    /** Package-private for tests. Events come from any thread: background subagents run on their own (spec 027). */
    synchronized void onEvent(Event e) {
        // The subagent being followed is shown like the main loop: its reply streams, then its steps (spec 030).
        boolean followed = e.agent().equals(following);
        if (!followed && !e.agent().equals("main") && (background.contains(e.agent())
                || e.type().equals("subagent.started") && e.body().path("background").asBoolean(false))) {
            backgroundEvent(e);
            return;
        }
        if (followed && e.type().equals("model.delta")) {
            closeThinking();
            stream(e.body().get("text").asText());
            return;
        }
        if (e.type().equals("model.thinking_delta")) {
            if (showThinking) {
                if (!thinkingOpen) {
                    out.println();
                    thinkingOpen = true;
                }
                raw.print(styler.thinking(e.body().get("text").asText()));
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
        // A subagent's lines say whose they are (spec 026).
        String who = e.agent().equals("main") ? "" : "[" + e.agent() + "] ";
        switch (e.type()) {
            case "subagent.started" -> {
                if (steps) {
                    String task = e.body().get("task").asText().strip().replaceAll("\\s+", " ");
                    String agent = e.body().get("customAgent").asText();
                    out.println();
                    raw.println(styler.step("▸ " + who + "started" + (agent.equals("default") ? "" : " as " + agent) + ": "
                            + (task.length() > 100 ? task.substring(0, 100) + "…" : task)));
                }
            }
            case "subagent.completed" -> {
                if (steps) {
                    String stop = e.body().get("stopReason").asText();
                    String text = "▸ " + who + stop + " after " + e.body().get("steps").asInt() + " steps";
                    raw.println(stop.equals("done") ? styler.step(text) : styler.error(text));
                }
            }
            case "loop.code" -> {
                if (steps) {
                    out.println();
                    raw.println(styler.step("▸ " + who + "step " + e.body().get("step").asInt() + ": running " + e.body().get("lines").asInt() + " lines"));
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
                    raw.print(styler.diff(diff));
                }
            }
            case "loop.result" -> {
                String status = e.body().get("status").asText();
                String limit = e.body().has("limit") ? " (" + e.body().get("limit").asText() + ")" : "";
                if (steps) {
                    String text = "▸ " + who + "step " + e.body().get("step").asInt() + ": " + status + limit;
                    raw.println(status.equals("ok") ? styler.step(text) : styler.error(text));
                }
                if (e.body().has("error")) {
                    int line = e.body().path("line").asInt(0);
                    raw.println(styler.error("  " + (line > 0 ? "line " + line + ": " : "") + e.body().get("error").asText()));
                }
            }
            case "model.retry" -> {
                if (steps) {
                    long s = (e.body().get("waitMs").asLong() + 999) / 1000;
                    raw.println(styler.dim("▸ " + e.body().get("reason").asText() + " from " + e.body().get("provider").asText()
                            + ", retrying in " + s + " s (attempt " + e.body().get("attempt").asInt() + " of "
                            + e.body().get("attempts").asInt() + ")"));
                }
            }
            default -> {
                // Other events go to the session log only, unless all events are shown.
            }
        }
        if (events.equals("all")) {
            raw.println(styler.dim("· " + e.type() + " " + e.body()));
        }
        out.flush();
    }

    /** A background subagent's start and end, above the prompt if the user is typing; its other events stay in the log. */
    private void backgroundEvent(Event e) {
        String who = "[" + e.agent() + "] ";
        String text = switch (e.type()) {
            case "subagent.started" -> {
                background.add(e.agent());
                String task = e.body().get("task").asText().strip().replaceAll("\\s+", " ");
                String agent = e.body().get("customAgent").asText();
                yield styler.step("▸ " + who + "started in the background" + (agent.equals("default") ? "" : " as " + agent) + ": "
                        + (task.length() > 100 ? task.substring(0, 100) + "…" : task));
            }
            case "subagent.completed" -> {
                String stop = e.body().get("stopReason").asText();
                String t = "▸ " + who + stop + " after " + e.body().get("steps").asInt() + " steps (/agents " + e.agent() + " shows its events)";
                yield stop.equals("done") ? styler.step(t) : styler.error(t);
            }
            default -> events.equals("all") ? styler.dim("· " + who + e.type() + " " + e.body()) : null;
        };
        if (text == null || events.equals("none") && !e.type().equals("subagent.completed")) {
            return;
        }
        flushPending();
        if (reader.isReading()) {
            reader.printAbove(text);
        } else {
            raw.println(text);
            out.flush();
        }
    }

    Terminal terminal() {
        return terminal;
    }
}
