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

    /**
     * Prefix on every call: no hooks, no fsmonitor, no transports, no pager, no signing or signature
     * checks (they run {@code gpg.program}), no submodule recursion, no background maintenance,
     * literal pathspecs (spec 015).
     */
    static final List<String> HARDENING = List.of(
            "-c", "core.hooksPath=/dev/null",
            "-c", "core.fsmonitor=false",
            "-c", "protocol.allow=never",
            "-c", "protocol.ext.allow=never",
            "-c", "core.pager=cat",
            "-c", "credential.helper=",
            "-c", "commit.gpgSign=false",
            "-c", "tag.gpgSign=false",
            "-c", "log.showSignature=false",
            "-c", "submodule.recurse=false",
            "-c", "gc.auto=0",
            "-c", "maintenance.auto=false",
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
                a.addAll(List.of("status", "--porcelain=v1", "--branch", "--ignore-submodules=all"));
                paths(a, req, false);
            }
            case "diff" -> {
                a.addAll(List.of("diff", "--no-color", "--no-ext-diff", "--no-textconv", "--ignore-submodules=all"));
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
        List<String> args = args(req);
        Duration t = CallTimeout.of(req, timeout, max);
        Map<String, String> env = env();
        List<String> argv = new ArrayList<>(HARDENING);
        argv.addAll(filterOverrides(repoFilters(runner.run(git, FILTER_QUERY, workspace.root(), t, env))));
        argv.addAll(args);
        return runner.run(git, argv, workspace.root(), t, env);
    }

    /** Lists every {@code filter.*} key with the scope it comes from. Exit 1 means none. */
    static final List<String> FILTER_QUERY = concat(HARDENING,
            List.of("config", "--show-scope", "--name-only", "--get-regexp", "^filter\\."));

    /** Scopes that come from the repository rather than the user (D-052). */
    private static final Set<String> REPO_SCOPES = Set.of("local", "worktree");

    /**
     * The filter drivers the repository's own config defines, from {@link #FILTER_QUERY} output
     * ({@code local<TAB>filter.<driver>.<key>} lines). Driver names may contain dots.
     */
    static Set<String> repoFilters(ProcessResult r) {
        if (r.exitCode() == 1 && r.full().isBlank()) {
            return Set.of();
        }
        if (r.exitCode() != 0) {
            throw new OperationException("git config failed (exit " + r.exitCode() + "): " + r.output().strip());
        }
        Set<String> drivers = new java.util.TreeSet<>();
        for (String line : r.full().split("\n")) {
            int tab = line.indexOf('\t');
            if (tab < 0 || !REPO_SCOPES.contains(line.substring(0, tab))) {
                continue;
            }
            String key = line.substring(tab + 1).strip();
            int last = key.lastIndexOf('.');
            if (key.startsWith("filter.") && last > "filter.".length()) {
                drivers.add(key.substring("filter.".length(), last));
            }
        }
        return drivers;
    }

    /** Empty {@code clean}, {@code smudge} and {@code process} turn a driver off (AC-01). */
    static List<String> filterOverrides(Set<String> drivers) {
        List<String> a = new ArrayList<>();
        for (String d : drivers) {
            for (String k : List.of("clean=", "smudge=", "process=", "required=false")) {
                a.addAll(List.of("-c", "filter." + d + "." + k));
            }
        }
        return a;
    }

    /** {@link #ENV}, plus {@code GIT_WORK_TREE} when the workspace is the repository root (AC-03). */
    Map<String, String> env() {
        if (!java.nio.file.Files.exists(workspace.root().resolve(".git"), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return ENV;
        }
        Map<String, String> e = new java.util.HashMap<>(ENV);
        e.put("GIT_WORK_TREE", workspace.root().toString());
        return Map.copyOf(e);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> r = new ArrayList<>(a);
        r.addAll(b);
        return List.copyOf(r);
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
