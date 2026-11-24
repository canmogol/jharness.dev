package dev.jharness.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import com.fasterxml.jackson.databind.JsonNode;

import dev.jharness.events.Json;
import dev.jharness.events.SessionLogs;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/** {@code jharness log [SESSION]} (spec 021): lists sessions, or prints one as a timeline. Exit codes: 0, 2 bad argument. */
@Command(name = "log", mixinStandardHelpOptions = true,
        description = {"List this folder's sessions, or show one session's events.",
                "SESSION is an id, a unique prefix of one, \"last\", or a path to a .jsonl log."})
final class LogCommand implements Callable<Integer> {

    @ParentCommand
    Main parent;

    @Parameters(arity = "0..1", paramLabel = "SESSION", description = "The session to show (default: list sessions).")
    String session;

    @Option(names = "--cwd", paramLabel = "DIR", description = "Workspace folder (default: the current folder).")
    Path cwd;

    @Option(names = "--home", hidden = true, paramLabel = "DIR", description = "Tests only: use DIR instead of the home folder.")
    Path home;

    @Option(names = "--all", description = "List the sessions of every project.")
    boolean all;

    @Option(names = "--limit", paramLabel = "N", defaultValue = "20", description = "Sessions to list (default: 20).")
    int limit;

    @Option(names = "--type", paramLabel = "PREFIX", description = "Show only event types starting with PREFIX (repeatable), e.g. network. or model.error.")
    List<String> types = new ArrayList<>();

    @Option(names = "--agent", paramLabel = "NAME", description = "Show only this agent's events (repeatable): main, or a subagent such as sub-1.")
    List<String> agents = new ArrayList<>();

    @Option(names = "--source", paramLabel = "SOURCE", description = "Show only events JHarness wrote (host) or the agent's code sent with api.emit (guest).")
    String source;

    @Option(names = "--full", description = "Show whole event bodies instead of the main fields.")
    boolean full;

    @Option(names = "--json", description = "Print the log lines as JSON, control characters escaped.")
    boolean json;

    /** Tests set the zone; null means the system's. */
    ZoneId zone;

    private static final DateTimeFormatter STARTED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Override
    public Integer call() {
        PrintStream out = parent.out;
        PrintStream err = parent.err;
        Path h = home != null ? home : parent.home != null ? parent.home : Path.of(System.getProperty("user.home"));
        Path c = cwd != null ? cwd : parent.cwd;
        Path ws = (c != null ? c : Path.of("")).toAbsolutePath().normalize();
        ZoneId z = zone != null ? zone : ZoneId.systemDefault();
        if (limit < 1) {
            err.println("jharness log: --limit must be at least 1");
            return 2;
        }
        if (source != null && !source.equals("host") && !source.equals("guest")) {
            err.println("jharness log: --source must be host or guest");
            return 2;
        }
        try {
            if (session == null) {
                if (!all && !Files.isDirectory(ws)) {
                    err.println("jharness log: " + ws + " is not a folder");
                    return 2;
                }
                list(out, h, ws, z);
                return 0;
            }
            Path file = SessionLogs.resolve(h, ws, session);
            show(out, file, z);
            return 0;
        } catch (SessionLogs.LookupException e) {
            err.println("jharness log: " + e.getMessage());
            return 2;
        } catch (IOException | RuntimeException e) {
            err.println("jharness log: " + e.getMessage());
            return 2;
        }
    }

    /** One log line: the parsed event, or null when the line isn't JSON. */
    record Line(int number, JsonNode event, String raw) {}

    static List<Line> read(Path file) throws IOException {
        List<Line> out = new ArrayList<>();
        int n = 0;
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            n++;
            if (raw.isBlank()) {
                continue;
            }
            JsonNode e = null;
            try {
                JsonNode p = Json.parse(raw);
                if (p.isObject()) {
                    e = p;
                }
            } catch (RuntimeException ignored) {
                // Shown as "not JSON".
            }
            out.add(new Line(n, e, raw));
        }
        return out;
    }

    private void list(PrintStream out, Path h, Path ws, ZoneId z) throws IOException {
        var entries = all ? SessionLogs.all(h) : SessionLogs.project(h, ws);
        if (entries.isEmpty()) {
            out.println(all ? "No sessions yet." : "No sessions for " + TerminalSafe.escape(ws.toString()) + ".");
            return;
        }
        out.println((all ? "Sessions of every project" : "Sessions in " + TerminalSafe.escape(ws.toString())) + ", newest first:");
        String fmt = "%-26s  %-16s  %-32s  %5s  %6s  %6s%s%n";
        out.printf(fmt, "ID", "STARTED", "MODEL", "TURNS", "EVENTS", "ERRORS", all ? "  WORKSPACE" : "");
        for (var e : entries.subList(0, Math.min(limit, entries.size()))) {
            String model = "";
            String workspace = "";
            int turns = 0;
            int events = 0;
            int errors = 0;
            for (Line l : read(e.file())) {
                if (l.event() == null) {
                    continue;
                }
                events++;
                turns = Math.max(turns, l.event().path("turn").asInt());
                if (LogFormat.error(l.event())) {
                    errors++;
                }
                String t = l.event().path("type").asText();
                if (t.equals("session.started") || t.equals("session.model_changed")) {
                    model = l.event().path("provider").asText() + "/" + l.event().path("model").asText();
                }
                if (t.equals("session.started")) {
                    workspace = l.event().path("workspace").asText();
                }
            }
            out.printf(fmt, TerminalSafe.escape(e.id()), STARTED.format(e.started().atZone(z)), TerminalSafe.escape(LogFormat.cut(model, 32)),
                    turns, events, errors, all ? "  " + TerminalSafe.escape(workspace) : "");
        }
        if (entries.size() > limit) {
            out.println((entries.size() - limit) + " older; --limit N shows more.");
        }
        out.println("jharness log SESSION shows one (an id, a unique prefix or \"last\").");
    }

    private void show(PrintStream out, Path file, ZoneId z) throws IOException {
        List<Line> lines = read(file);
        LogFormat f = new LogFormat(z, full);
        if (!json) {
            out.println("Session log " + TerminalSafe.escape(file.toString()));
        }
        for (Line l : lines) {
            if (l.event() == null) {
                out.println(json ? TerminalSafe.escapeJson(l.raw()) : "line " + l.number() + ": not JSON");
                continue;
            }
            String type = l.event().path("type").asText();
            if (!types.isEmpty() && types.stream().noneMatch(type::startsWith)) {
                continue;
            }
            if (!agents.isEmpty() && !agents.contains(l.event().path("agent").asText("main"))) {
                continue;
            }
            if (source != null && !source.equals(l.event().path("source").asText())) {
                continue;
            }
            out.println(json ? TerminalSafe.escapeJson(Json.write(l.event())) : f.line(l.event()));
        }
    }
}
