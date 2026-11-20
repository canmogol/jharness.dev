package dev.jharness.process;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.jharness.events.Json;
import dev.jharness.security.Operation;
import dev.jharness.security.Workspace;

/**
 * Typed, local-only git operations (D-008, D-039). Requests are JSON objects such as
 * {@code {"op":"diff","staged":true,"paths":["src"]}}; free-form arguments are never accepted.
 */
public final class GitOperations {

    /** Prefix on every call: no hooks, no fsmonitor, no transports, no pager, literal pathspecs. */
    static final List<String> HARDENING = List.of(
            "-c", "core.hooksPath=/dev/null",
            "-c", "core.fsmonitor=false",
            "-c", "protocol.allow=never",
            "-c", "protocol.ext.allow=never",
            "-c", "core.pager=cat",
            "-c", "credential.helper=",
            "--no-pager",
            "--literal-pathspecs");

    static final Map<String, String> ENV = Map.of(
            "GIT_CONFIG_NOSYSTEM", "1",
            "GIT_TERMINAL_PROMPT", "0",
            "GIT_OPTIONAL_LOCKS", "0");

    private static final Map<String, Set<String>> FIELDS = Map.of(
            "status", Set.of("paths"),
            "diff", Set.of("staged", "stat", "rev", "paths"),
            "log", Set.of("max", "oneline", "rev", "paths"),
            "show", Set.of("rev", "stat"),
            "branch", Set.of(),
            "add", Set.of("paths"),
            "commit", Set.of("message"),
            "restore", Set.of("staged", "paths"));

    private static final Set<String> WRITES = Set.of("add", "commit", "restore");

    /** Revisions: names, hashes and the usual suffixes; never starting with '-'. */
    private static final String REV = "[A-Za-z0-9_][A-Za-z0-9_./~^@{}:-]{0,199}";

    private final ProcessRunner runner;
    private final Executable git;
    private final Workspace workspace;
    private final Duration timeout;
    private final Duration max;

    public GitOperations(ProcessRunner runner, Executable git, Workspace workspace, Duration timeout) {
        this(runner, git, workspace, timeout, Duration.ofMinutes(10));
    }

    /** @param max the most {@code timeoutMs} may ask for (D-028) */
    public GitOperations(ProcessRunner runner, Executable git, Workspace workspace, Duration timeout, Duration max) {
        if (!git.name().equals("git")) {
            throw new IllegalArgumentException("not git: " + git);
        }
        this.runner = runner;
        this.git = git;
        this.workspace = workspace;
        this.timeout = timeout;
        this.max = max;
    }

    public static Set<String> operations() {
        return FIELDS.keySet();
    }

    /** The permission kind of a request, checked before {@link #run}. */
    public static Operation.Kind kind(ObjectNode req) {
        return WRITES.contains(op(req)) ? Operation.Kind.GIT_WRITE : Operation.Kind.GIT_READ;
    }

    static String op(ObjectNode req) {
        JsonNode op = req.get("op");
        if (op == null || !op.isTextual() || !FIELDS.containsKey(op.asText())) {
            throw new OperationException("git op must be one of " + String.join(", ", new java.util.TreeSet<>(FIELDS.keySet()))
                    + " (network operations come with the egress proxy)");
        }
        return op.asText();
    }

    /** The argv after the hardening prefix, validated. Exposed for tests. */
    List<String> args(ObjectNode req) {
        String op = op(req);
        for (Iterator<String> it = req.fieldNames(); it.hasNext(); ) {
            String f = it.next();
            if (!f.equals("op") && !f.equals(CallTimeout.FIELD) && !FIELDS.get(op).contains(f)) {
                List<String> takes = new ArrayList<>(new java.util.TreeSet<>(FIELDS.get(op)));
                takes.add(CallTimeout.FIELD);
                throw new OperationException("git " + op + " does not take \"" + f + "\" (takes: " + String.join(", ", takes) + ")");
            }
        }
        List<String> a = new ArrayList<>();
        switch (op) {
            case "status" -> {
                a.addAll(List.of("status", "--porcelain=v1", "--branch"));
                paths(a, req, false);
            }
            case "diff" -> {
                a.addAll(List.of("diff", "--no-color", "--no-ext-diff", "--no-textconv"));
                if (bool(req, "staged")) {
                    a.add("--staged");
                }
                if (bool(req, "stat")) {
                    a.add("--stat");
                }
                rev(a, req, false);
                paths(a, req, false);
            }
            case "log" -> {
                int max = req.has("max") ? req.get("max").asInt(-1) : 20;
                if (max < 1 || max > 1000) {
                    throw new OperationException("git log max must be 1 to 1000");
                }
                a.addAll(List.of("log", "--no-color", "--no-ext-diff", "--no-textconv", "-n", Integer.toString(max)));
                if (bool(req, "oneline")) {
                    a.add("--oneline");
                }
                rev(a, req, false);
                paths(a, req, false);
            }
            case "show" -> {
                a.addAll(List.of("show", "--no-color", "--no-ext-diff", "--no-textconv"));
                if (bool(req, "stat")) {
                    a.add("--stat");
                }
                rev(a, req, true);
            }
            case "branch" -> a.addAll(List.of("branch", "--list", "--no-color"));
            case "add" -> {
                a.add("add");
                paths(a, req, true);
            }
            case "commit" -> {
                JsonNode m = req.get("message");
                if (m == null || !m.isTextual() || m.asText().isBlank()) {
                    throw new OperationException("git commit needs a non-empty \"message\"");
                }
                if (m.asText().indexOf('\0') >= 0) {
                    throw new OperationException("commit message contains a NUL character");
                }
                // One argv element: the message can never be read as an option.
                a.addAll(List.of("commit", "--no-verify", "--message=" + m.asText()));
            }
            case "restore" -> {
                a.add("restore");
                if (bool(req, "staged")) {
                    a.add("--staged");
                }
                paths(a, req, true);
            }
            default -> throw new AssertionError(op);
        }
        return a;
    }

    public ProcessResult run(ObjectNode req) {
        List<String> argv = new ArrayList<>(HARDENING);
        argv.addAll(args(req));
        return runner.run(git, argv, workspace.root(), CallTimeout.of(req, timeout, max), ENV);
    }

    /** Result as the JSON string {@code api.git} returns. */
    public static String toJson(ProcessResult r) {
        return Json.write(Json.object().put("exitCode", r.exitCode()).put("output", r.output())
                .put("truncated", r.truncated()).put("timedOut", r.timedOut()).put("durationMs", r.durationMs()));
    }

    private static boolean bool(ObjectNode req, String f) {
        JsonNode n = req.get(f);
        if (n == null) {
            return false;
        }
        if (!n.isBoolean()) {
            throw new OperationException("\"" + f + "\" must be true or false");
        }
        return n.asBoolean();
    }

    private static void rev(List<String> a, ObjectNode req, boolean required) {
        JsonNode r = req.get("rev");
        if (r == null) {
            if (required) {
                throw new OperationException("\"rev\" is required");
            }
            return;
        }
        if (!r.isTextual() || !r.asText().matches(REV) || r.asText().contains("..") && r.asText().startsWith(".")) {
            throw new OperationException("\"rev\" must be a branch, tag, hash or range such as main..HEAD, not an option");
        }
        a.add(r.asText());
    }

    private void paths(List<String> a, ObjectNode req, boolean required) {
        JsonNode p = req.get("paths");
        if (p == null) {
            if (required) {
                throw new OperationException("\"paths\" is required");
            }
            return;
        }
        if (!p.isArray() || p.isEmpty() && required) {
            throw new OperationException("\"paths\" must be a list of workspace paths");
        }
        List<String> rel = new ArrayList<>();
        for (JsonNode n : p) {
            if (!n.isTextual()) {
                throw new OperationException("\"paths\" must be strings");
            }
            Path real = workspace.resolve(n.asText());
            rel.add(workspace.relative(real));
        }
        if (!rel.isEmpty()) {
            a.add("--");
            a.addAll(rel);
        }
    }
}
