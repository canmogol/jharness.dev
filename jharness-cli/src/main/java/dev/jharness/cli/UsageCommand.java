package dev.jharness.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.jharness.events.Json;
import dev.jharness.events.SessionLogs;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
 * {@code jharness usage [SESSION]} (spec 049, D-084): tokens and cost added up from the session logs, one row per
 * session, or one row per agent of one session. Exit codes: 0, 2 bad argument.
 */
@Command(name = "usage", mixinStandardHelpOptions = true,
        description = {"Show the tokens and cost of this folder's recent sessions, or of one session by agent.",
                "Costs come from the \"price\" of each model profile; requests without one show no cost.",
                "SESSION is an id, a unique prefix of one, \"last\", or a path to a .jsonl log."})
final class UsageCommand implements Callable<Integer> {

    @ParentCommand
    Main parent;

    @Parameters(arity = "0..1", paramLabel = "SESSION", description = "The session to show by agent (default: list sessions).")
    String session;

    @Option(names = "--cwd", paramLabel = "DIR", description = "Workspace folder (default: the current folder).")
    Path cwd;

    @Option(names = "--home", hidden = true, paramLabel = "DIR", description = "Tests only: use DIR instead of the home folder.")
    Path home;

    @Option(names = "--all", description = "Count the sessions of every project.")
    boolean all;

    @Option(names = "--days", paramLabel = "N", defaultValue = "30", description = "Sessions started in the last N days (default: 30).")
    int days;

    @Option(names = "--json", description = "Print one JSON object instead of a table.")
    boolean json;

    /** Tests set the clock and zone; null means now and the system's. */
    Instant now;
    ZoneId zone;

    private static final DateTimeFormatter STARTED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    static final int MAX_DAYS = 3650;

    @Override
    public Integer call() {
        PrintStream out = parent.out;
        PrintStream err = parent.err;
        Path h = home != null ? home : parent.home != null ? parent.home : Path.of(System.getProperty("user.home"));
        Path c = cwd != null ? cwd : parent.cwd;
        Path ws = (c != null ? c : Path.of("")).toAbsolutePath().normalize();
        ZoneId z = zone != null ? zone : ZoneId.systemDefault();
        if (days < 1 || days > MAX_DAYS) {
            err.println("jharness usage: --days must be 1 to " + MAX_DAYS);
            return 2;
        }
        try {
            if (session != null) {
                one(out, SessionLogs.resolve(h, ws, session));
                return 0;
            }
            if (!all && !Files.isDirectory(ws)) {
                err.println("jharness usage: " + ws + " is not a folder");
                return 2;
            }
            list(out, h, ws, z);
            return 0;
        } catch (SessionLogs.LookupException | IOException | RuntimeException e) {
            err.println("jharness usage: " + e.getMessage());
            return 2;
        }
    }

    private void one(PrintStream out, Path log) throws IOException {
        Usage u = Usage.ofLog(log);
        String id = log.getFileName().toString().replaceFirst("\\.jsonl$", "");
        if (json) {
            ObjectNode o = Json.object().put("session", id);
            var a = o.putObject("agents");
            u.agents.forEach((k, v) -> a.set(k, v.json()));
            o.set("total", u.total.json());
            out.println(TerminalSafe.escapeJson(Json.write(o)));
            return;
        }
        if (u.total.requests == 0) {
            out.println("Session " + TerminalSafe.escape(id) + " made no model requests.");
            return;
        }
        out.println("Usage of " + TerminalSafe.escape(id) + ":");
        Usage.table("AGENT", u.agents, u.total).forEach(out::println);
        Usage.notes(u.total).forEach(out::println);
    }

    /** One session's row: where it ran, its last model and its usage. */
    record Row(String id, Instant started, String workspace, String model, Usage usage) {}

    private void list(PrintStream out, Path h, Path ws, ZoneId z) throws IOException {
        Instant since = (now != null ? now : Instant.now()).minusSeconds(days * 86_400L);
        List<Row> rows = new ArrayList<>();
        Usage.Tally total = new Usage.Tally();
        for (var e : all ? SessionLogs.all(h) : SessionLogs.project(h, ws)) {
            if (e.started().isBefore(since)) {
                continue;
            }
            Usage u = new Usage();
            String model = "";
            String workspace = "";
            for (LogCommand.Line l : LogCommand.read(e.file())) {
                if (l.event() == null) {
                    continue;
                }
                u.add(l.event());
                String t = l.event().path("type").asText();
                if (t.equals("session.started") || t.equals("session.model_changed")) {
                    model = l.event().path("provider").asText() + "/" + l.event().path("model").asText();
                }
                if (t.equals("session.started")) {
                    workspace = l.event().path("workspace").asText();
                }
            }
            if (u.total.requests > 0) {
                rows.add(new Row(e.id(), e.started(), workspace, model, u));
                total.add(u.total);
            }
        }
        String where = all ? "every project" : TerminalSafe.escape(ws.toString());
        if (json) {
            ObjectNode o = Json.object().put("days", days);
            var a = o.putArray("sessions");
            for (Row r : rows) {
                ObjectNode s = a.addObject().put("session", r.id()).put("started", r.started().toString()).put("model", r.model());
                if (all) {
                    s.put("workspace", r.workspace());
                }
                s.set("usage", r.usage().total.json());
            }
            o.set("total", total.json());
            out.println(TerminalSafe.escapeJson(Json.write(o)));
            return;
        }
        if (rows.isEmpty()) {
            out.println("No model requests in the last " + days + " days in " + where + ".");
            return;
        }
        out.println("Usage in " + where + ", last " + days + " days, newest first:");
        List<String> head = new ArrayList<>(List.of("SESSION", "STARTED", "MODEL", "REQUESTS", "INPUT", "OUTPUT", "CACHE-READ", "CACHE-WRITE", "COST"));
        if (all) {
            head.add("WORKSPACE");
        }
        TextTable t = new TextTable(head.toArray(String[]::new));
        for (Row r : rows) {
            List<String> v = new ArrayList<>(List.of(r.id(), STARTED.format(r.started().atZone(z)), LogFormat.cut(r.model(), 32)));
            v.addAll(Arrays.asList(Usage.row("", r.usage().total)).subList(1, 7));
            if (all) {
                v.add(r.workspace());
            }
            t.row(v.toArray(String[]::new));
        }
        if (rows.size() > 1) {
            List<String> v = new ArrayList<>(List.of("total", "", ""));
            v.addAll(Arrays.asList(Usage.row("", total)).subList(1, 7));
            if (all) {
                v.add("");
            }
            t.row(v.toArray(String[]::new));
        }
        t.lines().forEach(out::println);
        Usage.notes(total).forEach(out::println);
        out.println("jharness usage SESSION shows one session by agent.");
    }
}
