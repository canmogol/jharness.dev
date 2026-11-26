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
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Status;

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

    /** Commands in alphabetical order, each with its forms; then the keys (spec 046). */
    static final String HELP = """
            Commands:
              /agent          list custom agents (start one with jharness --agent NAME)
              /agents         list this session's subagents
              /agents NAME    show a subagent's last 20 events
              /answer [NAME]  answer a held question (main, or a background subagent's) and stay in the main chat
              /commands       list your slash commands from ~/.jharness/commands/ and the project's .jharness/commands/
              /compact        summarize the conversation now to free context
              /context        show how full the model's context is
              /diff           show the last turn's file changes
              /events         show the event level and the session log file
              /events LEVEL   show none, steps or all events
              /exit           quit (also /quit, /q, or Ctrl-D)
              /follow [NAME]  watch a background subagent live, like the main loop (Esc stops watching)
              /help           show this help (or type / and press Tab)
              /hooks          list the JS hooks in ~/.jharness/hooks/ and the project's .jharness/hooks/
              /mcp            list the MCP servers from your settings and their tools
              /model          list model profiles
              /model NAME     switch to another profile
              /network        list the network rules, built-in denies and this session's allows
              /network allow|deny X [--project]   add a rule (host, *.suffix, IP or CIDR, optional :port)
              /network remove X [--project]       remove the rules naming X
              /network reload re-read both rules files
              /plan           turn plan mode on or off (read-only; the agent proposes a plan for you to approve)
              /rewind N       undo every turn after turn N (add --force to overwrite your own edits)
              /stop NAME|all  stop a running background subagent, or all of them
              /switch NAME    talk to a subagent: your messages and its questions go to it (/switch main goes back)
              /thinking       show or hide the model's thinking
              /todos          show the agent's todo list
              /undo           undo the last turn's file changes and remove it from the conversation
              /usage          show this session's tokens and cost, for each agent
              /view           show this session in the browser, read-only (prints the address)
            Keys:
              @PATH           attach a file or folder to the prompt (Tab completes the path)
              Shift+Enter, Alt+Enter, Ctrl-J  new line; Enter sends
              Esc             stop the running turn (messages sent while it runs wait for it to end)
            """;

    /** Each command in {@link #HELP} and its first description line, for Tab completion (spec 024). */
    static final java.util.SequencedMap<String, String> COMMANDS = commands();

    /** Other names of built-in commands (spec 041); not listed in the help or completed. */
    static final Map<String, String> ALIASES = Map.of("/quit", "/exit", "/q", "/exit");

    /** Every name a built-in answers to, which a command file can't take (spec 041). */
    static final java.util.Set<String> BUILT_IN = builtIn();

    private static java.util.Set<String> builtIn() {
        var s = new java.util.TreeSet<String>(COMMANDS.keySet());
        s.addAll(ALIASES.keySet());
        return java.util.Collections.unmodifiableSet(s);
    }

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
    /** Under {@link #raw}: whole lines above the prompt while a turn runs in the background (spec 055). */
    private final AboveWriter above;
    /**
     * Whether the prompt stays live during main turns (spec 055): every terminal but a dumb one. A main turn then runs on
     * {@link #worker} while the prompt reads.
     */
    private final boolean live;
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
    /** The subagent the prompt talks to after {@code /switch}, or null for the main agent (spec 032). */
    private volatile String talking;

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
        // Tab lists the choices; Tab again cycles through them, highlighted (spec 041 AC-03).
        b.option(LineReader.Option.AUTO_LIST, true).option(LineReader.Option.AUTO_MENU, true).option(LineReader.Option.MENU_COMPLETE, false);
        this.reader = b.build();
        this.live = !terminal.getType().startsWith("dumb");
        this.above = new AboveWriter(terminal.writer(), reader);
        this.raw = new PrintWriter(above, false);
        this.out = new PrintWriter(new TerminalSafe.SafeWriter(raw), false);
        this.styler = Styler.forTerminal(terminal, env);
        if (live) {
            multiLine();
            escKey();
        }
        slashTab();
    }

    /** Command names for a first word that starts with {@code /} (spec 024 AC-02); nothing otherwise. */
    static void completeCommand(LineReader r, org.jline.reader.ParsedLine line, List<org.jline.reader.Candidate> out) {
        if (line.word().startsWith("@")) {
            // A file or folder to attach (spec 040); a folder stays open so Tab can go on into it.
            Session s = current;
            if (s != null) {
                s.completeMention(line.word()).forEach(w -> out.add(new org.jline.reader.Candidate(w, w, null, null, null, null, !w.endsWith("/"))));
            }
            return;
        }
        if (line.wordIndex() > 0) {
            // A command's arguments (spec 041): its fixed choices, or names from the session.
            List<String> words = line.words();
            String cmd = ALIASES.getOrDefault(words.getFirst(), words.getFirst());
            List<String> choices = argChoices(cmd, words.subList(1, line.wordIndex()), current);
            for (int i = 0; i < choices.size(); i++) {
                String c = choices.get(i);
                out.add(new org.jline.reader.Candidate(c, c, null, null, null, null, true, i));
            }
            return;
        }
        if (!line.word().startsWith("/")) {
            return;
        }
        COMMANDS.forEach((name, desc) -> out.add(new org.jline.reader.Candidate(name, name, null, desc, null, null, true)));
        Session s = current;
        if (s != null) {
            s.commands(BUILT_IN).commands().values().forEach(c -> out.add(
                    new org.jline.reader.Candidate("/" + c.name(), "/" + c.name(), null, c.description(), null, null, true)));
        }
    }

    /**
     * The choices for the next argument of a command (spec 041 AC-03 to AC-05), in order.
     *
     * @param before the arguments already typed before the one being completed
     * @param s the running session, or null
     */
    static List<String> argChoices(String cmd, List<String> before, Session s) {
        int n = before.size();
        if (!cmd.startsWith("/")) {
            return List.of();
        }
        switch (cmd) {
            case "/events" -> {
                return n == 0 ? List.copyOf(UiSettings.EVENT_LEVELS) : List.of();
            }
            case "/model" -> {
                return n == 0 && s != null ? List.copyOf(s.models().all().keySet()) : List.of();
            }
            case "/agents", "/follow" -> {
                return n == 0 ? subagentNames(s) : List.of();
            }
            case "/answer" -> {
                if (n > 0 || s == null) {
                    return List.of();
                }
                return mainHeld.getAsBoolean() ? plus(List.of("main"), s.heldSubagents()) : s.heldSubagents();
            }
            case "/stop" -> {
                return n == 0 ? plus(subagentNames(s), "all") : List.of();
            }
            case "/switch" -> {
                return n == 0 ? java.util.stream.Stream.concat(java.util.stream.Stream.of("main"), subagentNames(s).stream()).toList() : List.of();
            }
            case "/rewind" -> {
                if (n == 0) {
                    return s == null ? List.of() : s.loop().turns().stream().map(String::valueOf).toList();
                }
                return n == 1 ? List.of("--force") : List.of();
            }
            case "/network" -> {
                if (n == 0) {
                    return List.of("allow", "deny", "remove", "reload");
                }
                String sub = before.getFirst();
                if (sub.equals("remove") && n == 1) {
                    return ruleTargets(s);
                }
                if (List.of("allow", "deny", "remove").contains(sub) && n == 2 && !before.contains("--project")) {
                    return List.of("--project");
                }
                return List.of();
            }
            default -> {
                if (COMMANDS.containsKey(cmd) || s == null || n > 0) {
                    return List.of();
                }
                var c = s.commands(BUILT_IN).get(cmd.substring(1));
                return c == null ? List.of() : c.args();
            }
        }
    }

    private static List<String> subagentNames(Session s) {
        return s == null ? List.of() : s.subagents().stream().map(Session.Subagent::name).toList();
    }

    private static List<String> plus(List<String> a, String b) {
        var out = new java.util.ArrayList<String>(a);
        out.add(b);
        return List.copyOf(out);
    }

    private static List<String> plus(List<String> a, List<String> b) {
        var out = new java.util.ArrayList<String>(a);
        out.addAll(b);
        return List.copyOf(out);
    }

    /** Whether the running TUI holds a main-agent question (spec 055), for Tab completion. */
    private static volatile java.util.function.BooleanSupplier mainHeld = () -> false;

    /** The hosts, suffixes and ranges named in both rules files, for {@code /network remove}. */
    private static List<String> ruleTargets(Session s) {
        EgressProxy p = s == null ? null : s.proxy();
        if (p == null) {
            return List.of();
        }
        var out = new java.util.LinkedHashSet<String>();
        for (RulesFile f : List.of(p.rules().general(), p.rules().project())) {
            for (Rule r : f.rules()) {
                String[] w = r.line().split("\\s+", 2);
                if (w.length == 2) {
                    out.add(w[1]);
                }
            }
        }
        return List.copyOf(out);
    }

    /** The running session, for Tab completion of the user's own commands. */
    private static volatile Session current;

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

    /**
     * Esc on the prompt stops the running main turn and denies its held question (spec 055 AC-03, AC-04). A lone Esc
     * also starts Alt keys, so JLine waits briefly to tell them apart.
     */
    private void escKey() {
        reader.getWidgets().put("jharness-esc", () -> {
            Session s = current;
            if (busy && s != null) {
                // Not on this thread: it holds JLine's lock, and stopping the isolate waits for the turn, which may be
                // printing above the prompt under that lock.
                TerminalPrompter p = prompter();
                Thread.ofPlatform().daemon().name("esc").start(() -> {
                    p.cancelHeld();
                    s.loop().interrupt();
                });
            }
            return true;
        });
        reader.getKeyMaps().get(LineReader.MAIN).bind(new Reference("jharness-esc"), "\u001b");
        reader.setVariable(LineReader.AMBIGUOUS_BINDING, 150L);
    }

    /** The trust question (AC-01, AC-02); end of input means no. */
    boolean askTrust(Path workspace) {
        return askTrust(workspace, List.of());
    }

    /** The trust question, listing the tools the project defines (spec 028, D-066). */
    boolean askTrust(Path workspace, List<dev.jharness.process.ToolDefinitions.ProjectTool> tools) {
        return askTrust(workspace, tools, List.of());
    }

    /** The trust question, listing the project's tools and its hooks (specs 028, 034). */
    boolean askTrust(Path workspace, List<dev.jharness.process.ToolDefinitions.ProjectTool> tools, List<String> hooks) {
        out.println("Do you trust " + workspace + "?");
        out.println("Trusting it lets its .jharness/settings.json loosen JHarness rules (permission mode, sandbox limits, mounts, models).");
        if (!tools.isEmpty()) {
            out.println("It also lets JHarness run the tools the project defines in .jharness/tools/:");
            tools.forEach(t -> out.println("  " + t.line()));
        }
        if (!hooks.isEmpty()) {
            out.println("It also runs the project's hooks on every api call (.jharness/hooks/): " + String.join(", ", hooks));
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
        current = session;
        logPath = session.logPath();
        out.println("JHarness in " + session.workspace().root() + " · model " + modelName(session)
                + (session.agent() != null ? " · agent " + session.agent().name() : "")
                + (session.offline() ? " · offline" : "") + " · /help for commands");
        raw.println(styler.dim("Session log: " + session.logPath()));
        var r = session.resumed();
        if (r != null) {
            var st = r.state();
            String last = st.conversation().stream().filter(m -> m.role().equals("assistant")).reduce((x, y) -> y)
                    .map(m -> m.content().strip().replaceAll("\\s+", " ")).orElse("");
            out.println((r.fork() ? "Forked from " : "Resumed ") + st.session() + ": " + st.turn() + (st.turn() == 1 ? " turn, " : " turns, ")
                    + st.conversation().size() + " messages" + (st.firstPrompt() != null ? "; it began with \"" + cut(st.firstPrompt(), 80) + "\"" : "") + ".");
            if (!last.isEmpty()) {
                raw.println(styler.dim(TerminalSafe.escape("Last reply: " + cut(last, 300))));
            }
            out.println("Earlier turns can't be undone with /undo or /rewind here.");
        }
        String reviewer = session.describeReviewer();
        if (reviewer != null) {
            out.println(reviewer);
        }
        var project = session.tools().project();
        if (!project.isEmpty()) {
            out.println("Project tools: " + String.join("; ", project.stream().map(dev.jharness.process.ToolDefinitions.ProjectTool::line).toList()));
        }
        session.tools().errors().forEach((n, err) -> raw.println(styler.error("Tool " + n + " is unavailable: " + err)));
        session.describeHooks().stream().filter(l -> l.contains("  broken: ")).forEach(l -> raw.println(styler.error("Hook " + l)));
        // A command file named like a built-in is loaded now, so the clash shows at the start (spec 041 AC-02).
        session.commands(BUILT_IN).clashes().forEach(c -> raw.println(styler.error(TerminalSafe.escape("Command " + c.file()
                + " is not used: /" + c.name() + " is a built-in command"))));
        startStatus(session);
        out.flush();
        // Spec 055: the main agent's questions during a background turn wait as notices.
        prompter().holds = new TerminalPrompter.Holds() {
            @Override
            public void waiting(dev.jharness.security.Operation op) {
                session.publish("question.waiting", dev.jharness.events.Json.object().put("api", op.api()).put("summary", op.summary()));
            }

            @Override
            public void ended(dev.jharness.security.Operation op, String outcome) {
                session.publish("question.wait_ended", dev.jharness.events.Json.object().put("api", op.api()).put("outcome", outcome));
            }
        };
        mainHeld = prompter()::holding;
        // Spec 054: a finished background subagent wakes an empty main prompt, so the main agent takes a turn with its reply.
        reader.getWidgets().put("jharness-deliver", () -> {
            Thread t = promptThread;
            if (t != null && talking == null && reader.getBuffer().length() == 0 && autoRun < session.settings().agent().autoTurns()) {
                t.interrupt();
            }
            return true;
        });
        session.onSubagentFinished(this::subagentFinished);
        try {
            loop(session);
        } finally {
            stopTurns(session);
            session.onSubagentFinished(null);
            mainHeld = () -> false;
            stopStatus();
            if (viewer != null) {
                viewer.close();
                viewer = null;
            }
        }
        try {
            reader.getHistory().save();
        } catch (IOException e) {
            out.println("could not save history: " + e.getMessage());
        }
        out.flush();
    }

    /** The status line (spec 042); null on dumb terminals, when {@code ui.statusLine} is off, and in {@code -p}. */
    private StatusLine statusLine;
    private Status status;
    private volatile List<AttributedString> statusLines = List.of();

    private void startStatus(Session session) {
        List<String> items = session.settings().ui().statusLine();
        if (terminal.getType().startsWith("dumb") || items.isEmpty()) {
            return;
        }
        StatusScript script = null;
        try {
            script = StatusScript.load(session.home(), session.settings().sandbox());
        } catch (IOException | RuntimeException e) {
            String why = "~/.jharness/statusline.js " + e.getMessage();
            session.bus().publish(dev.jharness.events.Origin.main(0), "statusline.failed",
                    dev.jharness.events.Json.object().put("reason", "file").put("error", why));
            raw.println(styler.error(TerminalSafe.escape("Status line script not used: " + why + "; showing the default line")));
        }
        status = Status.getStatus(terminal);
        reader.getWidgets().put("jharness-status", () -> {
            status.update(statusLines);
            // Called from the status thread while the prompt reads, which flushes only after a key (spec 055).
            terminal.flush();
            return true;
        });
        // When the prompt isn't reading, a resize is ours to handle; while it reads, JLine resizes the status itself.
        terminal.handle(Terminal.Signal.WINCH, sig -> {
            synchronized (this) {
                if (status != null) {
                    status.resize(terminal.getSize());
                }
            }
            StatusLine l = statusLine;
            if (l != null) {
                l.request();
            }
        });
        statusLine = new StatusLine(session, items, script, this::paintStatus, this::notice);
    }

    /** Draws the status line: through a widget while the prompt reads (JLine's lock), else under this TUI's lock. */
    private void paintStatus(String text) {
        AttributedString line = styler.color() ? new AttributedString(text, AttributedStyle.DEFAULT.faint()) : new AttributedString(text);
        statusLines = List.of(line);
        if (reader.isReading()) {
            try {
                reader.callWidget("jharness-status");
                return;
            } catch (IllegalStateException e) {
                // The prompt just ended; draw it the other way.
            }
        }
        synchronized (this) {
            if (status != null) {
                status.update(statusLines);
                terminal.flush();
            }
        }
    }

    /** A line above the prompt, from another thread. */
    private void notice(String text) {
        String line = styler.error(TerminalSafe.escape(text));
        if (reader.isReading()) {
            reader.printAbove(line);
        } else {
            synchronized (this) {
                raw.println(line);
                raw.flush();
            }
        }
    }

    private void stopStatus() {
        StatusLine l = statusLine;
        statusLine = null;
        if (l != null) {
            l.close();
        }
        synchronized (this) {
            if (status != null) {
                status.close();
                status = null;
                terminal.flush();
            }
        }
    }

    private void loop(Session session) {
        while (true) {
            if (live) {
                maybeAutoTurn(session);
            } else if (talking == null && session.deliveryPending() && autoTurn(session)) {
                continue;
            }
            String line;
            StatusLine sl = statusLine;
            if (sl != null) {
                sl.refreshNow();
            }
            try {
                line = readPrompt();
            } catch (UserInterruptException e) {
                continue;
            } catch (EndOfFileException e) {
                break;
            } finally {
                // A finished subagent may have woken the prompt (spec 054); the wake is spent either way.
                promptThread = null;
                Thread.interrupted();
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
            send(session, t);
        }
    }

    /**
     * A message for the agent the prompt talks to. On a live terminal a main-agent message runs on the worker, or waits
     * in the queue while a turn runs (spec 055 AC-02); otherwise the turn runs here.
     */
    private void send(Session session, String t) {
        if (talking != null) {
            ended(talkTurn(session, t));
            return;
        }
        if (!live) {
            autoRun = 0;
            autoCapped = false;
            ended(turn(session, t));
            return;
        }
        synchronized (turns) {
            if (stopping) {
                return;
            }
            if (busy) {
                queued.add(t);
                raw.println(styler.dim(TerminalSafe.escape("▸ queued: " + cut(t, 80) + " (it goes to the main agent when this turn ends; Esc stops the turn)")));
                out.flush();
                return;
            }
            autoRun = 0;
            autoCapped = false;
            startWorker(session, t);
        }
    }

    /** Guards {@link #busy}, {@link #queued}, {@link #stopping} and {@link #worker} (spec 055). */
    private final Object turns = new Object();
    /** Whether a main turn runs on the worker; the prompt reads meanwhile. */
    private volatile boolean busy;
    /** Messages sent during the running turn, in order; they go next, as one message. */
    private final List<String> queued = new java.util.ArrayList<>();
    /** Set when the session ends: no turn starts any more. */
    private boolean stopping;
    private Thread worker;

    /** Whether no main turn runs (spec 055); for tests. */
    boolean idle() {
        return !busy;
    }

    /** Under {@link #turns}: marks the TUI busy and starts the worker with a message, or with null for an automatic turn. */
    private void startWorker(Session session, String first) {
        busy = true;
        above.above(true);
        prompter().hold = true;
        worker = Thread.ofPlatform().daemon().name("main-turn").start(() -> work(session, first));
    }

    /**
     * The worker (spec 055): runs a turn, then the queued messages as the next one, then automatic turns while replies are
     * pending (spec 054), until there is nothing to do.
     */
    private void work(Session session, String first) {
        String text = first;
        try {
            while (true) {
                if (text != null) {
                    ended(turn(session, text));
                } else {
                    autoTurn(session);
                }
                synchronized (turns) {
                    if (!stopping && !queued.isEmpty()) {
                        text = String.join("\n\n", queued);
                        queued.clear();
                        autoRun = 0;
                        autoCapped = false;
                        continue;
                    }
                    if (!stopping && wantsAutoTurn(session)) {
                        text = null;
                        continue;
                    }
                    finishWork();
                    return;
                }
            }
        } catch (RuntimeException e) {
            synchronized (turns) {
                finishWork();
            }
            raw.println(styler.error(TerminalSafe.escape("[error: " + e + "]")));
            out.flush();
        }
    }

    /** Under {@link #turns}: the worker is done. */
    private void finishWork() {
        busy = false;
        // The prompt kept reading, so the status line is redrawn here, without "working".
        StatusLine sl = statusLine;
        if (sl != null) {
            sl.refreshNow();
        }
        prompter().hold = false;
        try {
            above.endLine();
        } catch (IOException e) {
            // The terminal is gone.
        }
        above.above(false);
        turns.notifyAll();
    }

    /** Under {@link #turns}: whether an automatic turn should run now; at the limit, says so once (spec 054 AC-03). */
    private boolean wantsAutoTurn(Session session) {
        if (talking != null || !session.deliveryPending()) {
            return false;
        }
        int max = session.settings().agent().autoTurns();
        if (autoRun < max) {
            return true;
        }
        if (max > 0 && !autoCapped) {
            raw.println(styler.error("▸ the main agent went on by itself " + max + (max == 1 ? " time" : " times")
                    + " in a row (agent.autoTurns); the next replies wait for your message"));
            out.flush();
        }
        autoCapped = true;
        return false;
    }

    /** Starts an automatic turn on the worker if a reply is pending and nothing runs (spec 055). */
    private void maybeAutoTurn(Session session) {
        synchronized (turns) {
            if (!busy && !stopping && wantsAutoTurn(session)) {
                startWorker(session, null);
            }
        }
    }

    /** {@code /exit}, Ctrl-D or the end of input (spec 055 AC-05): the running turn stops, and the session ends after it. */
    private void stopTurns(Session session) {
        Thread w;
        synchronized (turns) {
            stopping = true;
            queued.clear();
            w = worker;
            if (busy) {
                raw.println(styler.dim("Stopping the main agent's turn."));
                out.flush();
            }
        }
        if (w == null) {
            return;
        }
        prompter().cancelHeld();
        session.loop().interrupt();
        try {
            w.join(30_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Commands that change the conversation or take the prompt, which wait while a main turn runs (spec 055 AC-05). */
    private boolean waitsForTurn(String cmd, String arg) {
        boolean waits = switch (cmd) {
            case "/compact", "/diff", "/undo", "/rewind", "/plan", "/follow" -> true;
            case "/model" -> !arg.isEmpty();
            case "/switch" -> !arg.isEmpty() && !arg.equals("main");
            default -> false;
        };
        if (waits && busy) {
            out.println(cmd + " waits until the main agent's turn ends (Esc stops it).");
            return true;
        }
        return false;
    }

    private void ended(TurnResult r) {
        out.println();
        if (!r.stopReason().equals("done")) {
            raw.println(styler.error("[" + r.stopReason() + (r.error() == null ? "" : ": " + r.error()) + "]"));
        }
        out.flush();
    }

    /** Automatic main turns in a row since the user's last message (spec 054, {@code agent.autoTurns}). */
    private int autoRun;
    /** Whether the user was told that the limit of automatic turns was reached. */
    private boolean autoCapped;
    /** The thread reading the main prompt, while it reads; a finished subagent wakes it (spec 054). */
    private volatile Thread promptThread;

    /**
     * Background subagents finished (spec 054): the main agent takes a turn with their replies, unless {@code agent.autoTurns}
     * such turns already ran in a row since the user's last message.
     *
     * @return true if a turn ran
     */
    private boolean autoTurn(Session session) {
        int max = session.settings().agent().autoTurns();
        if (autoRun >= max) {
            if (max > 0 && !autoCapped) {
                raw.println(styler.error("▸ the main agent went on by itself " + max + (max == 1 ? " time" : " times")
                        + " in a row (agent.autoTurns); the next replies wait for your message"));
                out.flush();
            }
            autoCapped = true;
            return false;
        }
        autoRun++;
        raw.println(styler.step("▸ the main agent goes on with the finished subagents' replies (Esc stops it)"));
        out.flush();
        TurnResult r = turn(session, null);
        if (r == null) {
            autoRun--;
            return false;
        }
        if (r.stopReason().equals("interrupted")) {
            // Esc on an automatic turn stops the chain until the user writes again.
            autoRun = max;
            autoCapped = true;
        }
        ended(r);
        return true;
    }

    /**
     * On a subagent's thread when a background subagent finished (spec 054): if the user sits at an empty main prompt,
     * wakes it so the main agent can take its turn. Otherwise the reply waits: for the end of the running turn, or for
     * the message being typed, which brings it along.
     */
    private void subagentFinished() {
        if (live) {
            Session s = current;
            if (s != null) {
                maybeAutoTurn(s);
            }
            return;
        }
        // The prompt may be about to start reading; give it a moment.
        for (int i = 0; i < 100 && promptThread != null && !reader.isReading(); i++) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (promptThread == null || !reader.isReading()) {
            return;
        }
        try {
            reader.callWidget("jharness-deliver");
        } catch (IllegalStateException e) {
            // The prompt just ended; the loop delivers it next.
        }
    }

    /** The {@code > } prompt, with modified keys reported only while it reads (spec 014 AC-01). */
    private String readPrompt() {
        promptThread = Thread.currentThread();
        boolean keys = live;
        out.flush();
        try {
            above.endLine();
        } catch (IOException e) {
            // The terminal is gone; readLine says so.
        }
        // The key mode goes straight to the terminal, never into a line above the prompt.
        PrintWriter direct = terminal.writer();
        if (keys) {
            direct.print(MODIFY_KEYS_ON);
            direct.flush();
        }
        try {
            String t = talking;
            Session s = current;
            String plan = s != null && s.planMode() ? "[plan] " : "";
            return reader.readLine(plan + (t == null ? "> " : t + "> "));
        } finally {
            if (keys) {
                direct.print(MODIFY_KEYS_OFF);
                direct.flush();
            }
        }
    }

    /**
     * Runs a main turn. On a live terminal it runs on the worker while the prompt reads, and Esc on the prompt stops it
     * (spec 055); dumb terminals have no raw keys, so there is no Esc. A null text runs the turn with the finished
     * subagents' replies (spec 054), or returns null when there are none.
     */
    private TurnResult turn(Session session, String text) {
        styler.reset();
        prompter().enter();
        try {
            return text == null ? session.deliveryTurn(this::stream) : session.turn(text, this::stream);
        } finally {
            prompter().leave();
            synchronized (this) {
                flushPending();
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
    private synchronized void stream(String delta) {
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

    /** @return whether a partial line was printed */
    private boolean flushPending() {
        if (!pending.isEmpty()) {
            raw.print(styler.line(pending.toString()));
            pending.setLength(0);
            out.flush();
            return true;
        }
        return false;
    }

    private static String cut(String s, int max) {
        String one = s.strip().replaceAll("\\s+", " ");
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }

    private static String modelName(Session s) {
        return s.profile() != null ? s.profile() : s.loop().provider().provider();
    }

    /** @return false to quit */
    boolean command(Session session, String line) {
        String[] parts = line.split("\\s+", 2);
        parts[0] = ALIASES.getOrDefault(parts[0], parts[0]);
        if (waitsForTurn(parts[0], parts.length > 1 ? parts[1].strip() : "")) {
            out.flush();
            return true;
        }
        switch (parts[0]) {
            case "/exit" -> {
                return false;
            }
            case "/help" -> {
                out.print(HELP);
                List<String> c = session.commands(BUILT_IN).describe();
                if (!c.isEmpty()) {
                    out.println("Your commands:");
                    c.forEach(out::println);
                }
            }
            case "/context" -> {
                var c = session.loop().context();
                out.println(String.format("Context: %s%,d of %,d tokens (%d%%)%s", c.estimated() ? "about " : "", c.tokens(), c.window(),
                        (int) Math.round(100.0 * c.tokens() / c.window()),
                        c.autoCompact() ? String.format(" · compacts at about %,d", c.threshold()) : " · automatic compaction is off"));
                if (c.compactedThrough() > 0) {
                    out.println("Turns up to " + c.compactedThrough() + " are kept as a summary.");
                }
            }
            case "/compact" -> {
                if (!busy(session, "/compact")) {
                    out.println("Compacting…");
                    out.flush();
                    out.println(session.loop().compact());
                }
            }
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
                    try {
                        out.println(session.loop().rewind(Integer.parseInt(a[1]), line.contains("--force")).message());
                    } catch (IllegalArgumentException e) {
                        out.println(e.getMessage());
                    }
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
            case "/switch" -> switchTo(session, parts.length > 1 ? parts[1].strip() : "");
            case "/answer" -> answer(session, parts.length > 1 ? parts[1].strip() : "");
            case "/network" -> network(session, parts.length > 1 ? parts[1].strip() : "");
            case "/mcp" -> {
                List<String> m = session.describeMcp();
                if (m.isEmpty()) {
                    out.println("No MCP servers. Add them to \"mcp\": {\"servers\": {...}} in ~/.jharness/settings.json.");
                }
                m.forEach(out::println);
            }
            case "/plan" -> {
                try {
                    boolean on = session.togglePlanMode();
                    out.println(on ? "Plan mode is on: the agent can read but not change anything, and proposes a plan for you to approve."
                            : "Plan mode is off.");
                } catch (IllegalStateException e) {
                    out.println(e.getMessage());
                }
            }
            case "/todos" -> {
                String who = talking == null ? "main" : talking;
                List<String[]> items = session.todos(who);
                if (items.isEmpty()) {
                    out.println("No todo list" + (who.equals("main") ? "" : " for " + who) + " yet.");
                }
                todoLines(items).forEach(l -> raw.println(l));
            }
            case "/hooks" -> {
                List<String> h = session.describeHooks();
                if (h.isEmpty()) {
                    out.println("No hooks. Put NAME.js files with preTool, postTool or stop functions in ~/.jharness/hooks/.");
                }
                h.forEach(out::println);
            }
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
            case "/view" -> view(session);
            case "/usage" -> usage(session);
            case "/commands" -> {
                List<String> c = session.commands(BUILT_IN).describe();
                if (c.isEmpty()) {
                    out.println("No slash commands. Add NAME.md (a prompt; $ARGUMENTS is what you type after it) or NAME.js (run(args)) to ~/.jharness/commands/.");
                }
                c.forEach(out::println);
                session.commands(BUILT_IN).errors().forEach((f, err) -> raw.println(styler.error(f + ": " + err)));
            }
            default -> {
                if (!custom(session, parts[0].substring(1), parts.length > 1 ? parts[1].strip() : "")) {
                    out.println("Unknown command " + parts[0] + ". Type /help for the commands.");
                }
            }
        }
        out.flush();
        return true;
    }

    /**
     * A command from {@code ~/.jharness/commands/} or the project's (spec 035): its prompt runs as a turn
     * (to the switched-to subagent, if any), or its text is printed.
     *
     * @return false if there is no such command
     */
    private boolean custom(Session session, String name, String args) {
        var runner = session.commands(BUILT_IN);
        if (runner.get(name) == null) {
            return false;
        }
        dev.jharness.agent.CommandRunner.Expansion x;
        try {
            x = runner.run(name, args);
        } catch (RuntimeException e) {
            raw.println(styler.error(String.valueOf(e.getMessage())));
            return true;
        }
        if (x.print() != null) {
            out.println(x.print());
        } else if (x.prompt() != null && !x.prompt().isBlank()) {
            raw.println(styler.dim("/" + name + " → " + (x.prompt().length() > 100 ? x.prompt().substring(0, 100).replace('\n', ' ') + "…"
                    : x.prompt().replace('\n', ' '))));
            out.flush();
            send(session, x.prompt());
        }
        return true;
    }

    /** The browser view of this session (spec 048), started on the first {@code /view}. */
    private dev.jharness.web.SessionViewer viewer;

    private void view(Session session) {
        if (viewer == null) {
            try {
                viewer = dev.jharness.web.SessionViewer.start(LiveFeed.of(session), 0);
            } catch (IOException | RuntimeException e) {
                raw.println(styler.error("Can't start the viewer: " + TerminalSafe.escape(String.valueOf(e.getMessage()))));
                return;
            }
        }
        out.println("Open " + viewer.url());
        out.println("Read-only, on this machine only; it stops when you quit.");
    }

    /** {@code /usage} (spec 049): this session's log added up, so a resumed session counts its earlier runs too. */
    private void usage(Session session) {
        Usage u;
        try {
            u = Usage.ofLog(session.logPath());
        } catch (IOException | RuntimeException e) {
            raw.println(styler.error("Can't read the session log: " + TerminalSafe.escape(String.valueOf(e.getMessage()))));
            return;
        }
        if (u.total.requests == 0) {
            out.println("No model requests yet.");
            return;
        }
        out.println("Usage this session:");
        Usage.table("AGENT", u.agents, u.total).forEach(out::println);
        Usage.notes(u.total).forEach(out::println);
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

    /**
     * {@code /answer [NAME]} (spec 054): asks a background subagent's held question here, the oldest first, and leaves the
     * user in the main chat. The subagent asks on its own thread; this one waits for the answer.
     */
    private void answer(Session session, String name) {
        List<String> held = prompter().holding() ? plus(List.of("main"), session.heldSubagents()) : session.heldSubagents();
        if (held.isEmpty()) {
            out.println("No question is waiting for an answer.");
            return;
        }
        String n = name.isEmpty() ? held.getFirst() : name;
        if (!held.contains(n)) {
            out.println(n + " isn't waiting for an answer. Waiting: " + String.join(", ", held) + ".");
            return;
        }
        out.println(n.equals("main") ? "The main agent asks:" : n + " asks:");
        out.flush();
        // As while /switch watches a subagent: the terminal in raw mode, so the question's prompt and the status line
        // are drawn as in a turn.
        boolean keys = !terminal.getType().startsWith("dumb");
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
            w = new EscWatcher(terminal.reader(), () -> {});
            prompter().watcher = w;
        }
        prompter().enter();
        try {
            if (n.equals("main")) {
                prompter().answerHeld();
            } else {
                session.answer(n);
            }
        } finally {
            prompter().leave();
            synchronized (this) {
                flushPending();
            }
            if (w != null) {
                prompter().watcher = null;
                w.close();
                terminal.setAttributes(saved);
            }
        }
        List<String> more = prompter().holding() ? plus(List.of("main"), session.heldSubagents()) : session.heldSubagents();
        out.println(more.isEmpty() ? "Back to the main chat." : "Back to the main chat. Still waiting: " + String.join(", ", more) + " (/answer).");
        out.flush();
    }

    /**
     * {@code /switch NAME} (spec 032): the prompt talks to that subagent. Its output shows like the main
     * loop's and its questions are asked, not denied. A running subagent is watched until its turn ends;
     * Esc stops that turn. {@code /switch main} goes back.
     */
    private void switchTo(Session session, String name) {
        if (name.isEmpty()) {
            out.println("Usage: /switch NAME or /switch main. " + (talking == null ? "Talking to the main agent." : "Talking to " + talking + "."));
            return;
        }
        if (name.equals("main")) {
            if (talking != null) {
                out.println("Back to the main agent; " + talking + " keeps its conversation (/switch " + talking + " to go on).");
            }
            talking = null;
            following = null;
            session.attend(null);
            return;
        }
        if (!session.subagentOpen(name)) {
            boolean known = session.subagents().stream().anyMatch(a -> a.name().equals(name));
            out.println(known ? name + " is closed (its sandbox was freed); start a new subagent instead."
                    : "No subagent " + name + " in this session." + (session.subagents().isEmpty() ? ""
                            : " Subagents: " + String.join(", ", session.subagents().stream().map(Session.Subagent::name).toList()) + "."));
            return;
        }
        talking = name;
        following = name;
        boolean running = session.runningSubagents().contains(name);
        if (running) {
            // A held question (spec 033) is asked as soon as attend() wakes it, so the prompter must be ready first;
            // watch() leaves it.
            prompter().enter();
        }
        session.attend(name);
        Session.Subagent a = session.subagents().stream().filter(x -> x.name().equals(name)).findFirst().orElseThrow();
        String task = a.task().strip().replaceAll("\\s+", " ");
        out.println("Talking to " + name + " (" + (task.length() > 80 ? task.substring(0, 80) + "…" : task) + "). Your messages go to it and its "
                + "questions come to you; /switch main goes back.");
        if (running) {
            out.println(name + " is running; you can type when its turn ends" + (terminal.getType().startsWith("dumb") ? "." : " (Esc stops it)."));
            out.flush();
            watch(session, name, () -> session.interruptSubagent(name));
        } else if (!a.status().equals("running")) {
            raw.println(styler.dim("Its last reply ended with: " + a.status()));
        }
        out.flush();
    }

    /** One message to the subagent the user switched to, with Esc and approvals as in a main turn. */
    private TurnResult talkTurn(Session session, String text) {
        String name = talking;
        boolean keys = !terminal.getType().startsWith("dumb");
        Attributes saved = null;
        EscWatcher w = null;
        if (keys) {
            saved = terminal.getAttributes();
            Attributes a = new Attributes(saved);
            a.setLocalFlag(Attributes.LocalFlag.ICANON, false);
            a.setLocalFlag(Attributes.LocalFlag.ECHO, false);
            a.setControlChar(Attributes.ControlChar.VMIN, 0);
            a.setControlChar(Attributes.ControlChar.VTIME, 1);
            terminal.setAttributes(a);
            w = new EscWatcher(terminal.reader(), () -> session.interruptSubagent(name));
            prompter().watcher = w;
        }
        styler.reset();
        prompter().enter();
        try {
            // Its reply streams through the followed-agent events, so the callback prints nothing.
            return session.talk(name, text, d -> {});
        } catch (IllegalArgumentException e) {
            return new TurnResult("", "error", 0, e.getMessage());
        } finally {
            prompter().leave();
            synchronized (this) {
                flushPending();
            }
            if (w != null) {
                prompter().watcher = null;
                w.close();
                terminal.setAttributes(saved);
            }
        }
    }

    /** Waits, in raw mode with Esc watched, until the subagent's current turn ends; questions are asked meanwhile. */
    private void watch(Session session, String name, Runnable onEsc) {
        boolean keys = !terminal.getType().startsWith("dumb");
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
            w = new EscWatcher(terminal.reader(), onEsc);
            prompter().watcher = w;
        }
        try {
            while (session.runningSubagents().contains(name)) {
                Thread.sleep(100);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            // switchTo() entered.
            prompter().leave();
            synchronized (this) {
                flushPending();
            }
            if (w != null) {
                prompter().watcher = null;
                w.close();
                terminal.setAttributes(saved);
            }
        }
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
        StatusLine sl = statusLine;
        if (sl != null) {
            sl.onEvent(e);
        }
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
        boolean flushed = flushPending();
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
                    if (flushed) {
                        out.println();
                    }
                    String stop = e.body().get("stopReason").asText();
                    String text = "▸ " + who + stop + " after " + e.body().get("steps").asInt() + " steps";
                    raw.println(stop.equals("done") ? styler.step(text) : styler.error(text));
                }
            }
            case "subagent.waiting", "subagent.wait_ended" -> {
                String text = waitingLine(e, who);
                if (text != null) {
                    raw.println(text);
                }
            }
            case "question.waiting" -> raw.println(styler.error(TerminalSafe.escape("▸ the main agent is waiting for you: "
                    + e.body().get("summary").asText() + " (/answer main to answer; Esc stops the turn)")));
            case "question.wait_ended" -> {
                if (e.body().get("outcome").asText().equals("stopped")) {
                    raw.println(styler.error("▸ that question was denied"));
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
            case "todo.updated" -> {
                List<String[]> items = new java.util.ArrayList<>();
                e.body().get("items").forEach(i -> items.add(new String[] {i.get("status").asText(), i.get("text").asText()}));
                long done = items.stream().filter(i -> i[0].equals("done")).count();
                out.println();
                raw.println(styler.step("▸ " + who + "todos (" + done + "/" + items.size() + " done)"));
                todoLines(items).forEach(l -> raw.println(l));
            }
            case "plan.proposed" -> {
                out.println();
                raw.println(styler.step("▸ Plan:"));
                styler.reset();
                for (String l : e.body().get("plan").asText().split("\n", -1)) {
                    raw.println(styler.line(l));
                }
            }
            case "plan.mode" -> raw.println(styler.dim("▸ plan mode " + (e.body().get("plan").asBoolean() ? "on" : "off")
                    + (e.body().path("by").asText().equals("plan") ? ": the plan was approved" : "")));
            case "hook.ran" -> {
                String outcome = e.body().get("outcome").asText();
                String hook = e.body().get("hook").asText();
                String reason = e.body().path("reason").asText("");
                switch (outcome) {
                    case "block" -> raw.println(styler.error("▸ hook " + hook + " blocked a call: " + reason));
                    case "continue" -> raw.println(styler.step("▸ hook " + hook + " asks to go on: " + reason));
                    case "error" -> raw.println(styler.error("▸ hook " + hook + " failed in " + e.body().get("event").asText() + ": " + reason));
                    default -> {
                        // ok: only in the session log
                    }
                }
            }
            case "context.compacted" -> {
                if (e.body().get("by").asText().equals("auto")) {
                    raw.println(styler.dim(String.format("▸ %scontext compacted: about %,d → %,d tokens", who, e.body().get("tokensBefore").asInt(),
                            e.body().get("tokensAfter").asInt())));
                }
            }
            case "prompt.attached" -> {
                List<String> parts = new java.util.ArrayList<>();
                e.body().get("files").forEach(f -> parts.add(f.get("path").asText() + (f.get("folder").asBoolean() ? "/" : "")
                        + (f.get("truncated").asBoolean() ? " (cut)" : "")));
                if (!parts.isEmpty()) {
                    raw.println(styler.dim(TerminalSafe.escape(String.format("▸ attached %s (%,d characters)", String.join(", ", parts),
                            e.body().get("chars").asInt()))));
                }
                e.body().get("skipped").forEach(k -> raw.println(styler.error(TerminalSafe.escape("▸ not attached: @" + k.get("mention").asText()
                        + ": " + k.get("reason").asText()))));
            }
            case "context.compact_failed" -> raw.println(styler.error("▸ " + who + "compaction failed: " + e.body().get("error").asText()));
            case "review.verdict" -> {
                if (e.body().get("outcome").asText().equals("deny")) {
                    raw.println(styler.error(String.format("▸ reviewer refused %s (confidence %.2f)", e.body().get("api").asText(),
                            e.body().get("confidence").asDouble())));
                }
            }
            case "review.failed" -> raw.println(styler.error("▸ reviewer failed on " + e.body().get("api").asText() + ": "
                    + e.body().get("error").asText() + (e.body().get("outcome").asText().equals("deny") ? "; refused" : "; asking you")));
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

    /** A todo list as lines: done, in progress and pending marks (spec 036). */
    private List<String> todoLines(List<String[]> items) {
        List<String> out = new java.util.ArrayList<>();
        for (String[] i : items) {
            String mark = switch (i[0]) {
                case "done" -> "✓";
                case "in_progress" -> "▸";
                default -> "☐";
            };
            String line = "  " + mark + " " + i[1];
            // Styler escapes what it styles (spec 022); the plain line is escaped here.
            out.add(i[0].equals("done") ? styler.dim(line) : i[0].equals("in_progress") ? styler.step(line) : TerminalSafe.escape(line));
        }
        return out;
    }

    /** A held question (spec 033): the notice that it waits, and the line when no one answered in time. */
    private String waitingLine(Event e, String who) {
        if (e.type().equals("subagent.waiting")) {
            return styler.error("▸ " + who + "is waiting for you: " + e.body().get("summary").asText() + " (/answer " + e.agent() + " to answer)");
        }
        String outcome = e.body().get("outcome").asText();
        return outcome.equals("timeout") ? styler.error("▸ " + who + "no one answered in time; that question was denied") : null;
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
            case "subagent.waiting", "subagent.wait_ended" -> waitingLine(e, who);
            default -> events.equals("all") ? styler.dim("· " + who + e.type() + " " + e.body()) : null;
        };
        if (text == null || events.equals("none") && !e.type().equals("subagent.completed") && !e.type().startsWith("subagent.wait")) {
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
