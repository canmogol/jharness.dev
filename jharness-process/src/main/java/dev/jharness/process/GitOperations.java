package dev.jharness.process;

import java.net.URI;
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
 * Typed git operations (D-008, D-039, D-055). Requests are JSON objects such as
 * {@code {"op":"diff","staged":true,"paths":["src"]}}; free-form arguments are never accepted.
 * {@code fetch}, {@code pull}, {@code push} and {@code clone} reach HTTPS remotes through the egress
 * proxy only (spec 017); the rest never touch the network.
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

    private static final Map<String, Set<String>> FIELDS = Map.ofEntries(
            Map.entry("status", Set.of("paths")),
            Map.entry("diff", Set.of("staged", "stat", "rev", "paths")),
            Map.entry("log", Set.of("max", "oneline", "rev", "paths")),
            Map.entry("show", Set.of("rev", "stat")),
            Map.entry("branch", Set.<String>of()),
            Map.entry("add", Set.of("paths")),
            Map.entry("commit", Set.of("message")),
            Map.entry("restore", Set.of("staged", "paths")),
            Map.entry("fetch", Set.of("remote", "ref", "prune")),
            Map.entry("pull", Set.of("remote", "ref")),
            Map.entry("push", Set.of("remote", "ref", "setUpstream")),
            Map.entry("clone", Set.of("url", "dir")));

    private static final Set<String> WRITES = Set.of("add", "commit", "restore", "pull", "push", "clone");

    /** Operations that reach a remote (spec 017). */
    static final Set<String> NETWORK_OPS = Set.of("fetch", "pull", "push", "clone");

    /**
     * Added for network operations (AC-03): HTTPS is the only transport, redirects are off (a token
     * must not follow one to another host), and no cookie file is read.
     */
    static final List<String> NETWORK = List.of(
            "-c", "protocol.https.allow=always",
            "-c", "protocol.http.allow=never",
            "-c", "protocol.file.allow=never",
            "-c", "protocol.git.allow=never",
            "-c", "protocol.ssh.allow=never",
            "-c", "protocol.ext.allow=never",
            "-c", "http.followRedirects=false",
            "-c", "http.cookieFile=");

    /** Repository config keys that make git run a program, ask for secrets or change transports (AC-04). */
    static final String RISKY_KEYS = "^(filter|credential|protocol)\\.|^core\\.(askpass|sshcommand)$|^http\\..*cookiefile$"
            + "|^remote\\..*\\.(uploadpack|receivepack)$";

    private static final String REMOTE = "[A-Za-z0-9][A-Za-z0-9._-]{0,99}";
    /** Branch or tag names for fetch, pull and push: no refspecs, no '+', no ':'. */
    private static final String REF = "[A-Za-z0-9_][A-Za-z0-9_./-]{0,199}";
    private static final String DIR = "[A-Za-z0-9_][A-Za-z0-9._-]{0,99}";

    /** Revisions: names, hashes and the usual suffixes; never starting with '-'. */
    private static final String REV = "[A-Za-z0-9_][A-Za-z0-9_./~^@{}:-]{0,199}";

    private final ProcessRunner runner;
    private final Executable git;
    private final Workspace workspace;
    private final Duration timeout;
    private final Duration max;
    private final boolean offline;
    private final Map<String, String> tokens;
    private final List<Map.Entry<String, String>> extraConfig;

    public GitOperations(ProcessRunner runner, Executable git, Workspace workspace, Duration timeout) {
        this(runner, git, workspace, timeout, Duration.ofMinutes(10));
    }

    /** @param max the most {@code timeoutMs} may ask for (D-028) */
    public GitOperations(ProcessRunner runner, Executable git, Workspace workspace, Duration timeout, Duration max) {
        this(runner, git, workspace, timeout, max, false, Map.of());
    }

    /**
     * @param offline refuse network operations at once (D-033, D-055)
     * @param tokens host (optionally {@code host:port}) to token, from the user's {@code git.credentials} (AC-06)
     */
    public GitOperations(ProcessRunner runner, Executable git, Workspace workspace, Duration timeout, Duration max, boolean offline,
            Map<String, String> tokens) {
        this(runner, git, workspace, timeout, max, offline, tokens, List.of());
    }

    /**
     * @param extraConfig more {@code GIT_CONFIG_*} entries for network operations. Only tests and the
     *     smoke binary pass any (a CA file for their local HTTPS server); sessions never do
     */
    public GitOperations(ProcessRunner runner, Executable git, Workspace workspace, Duration timeout, Duration max, boolean offline,
            Map<String, String> tokens, List<Map.Entry<String, String>> extraConfig) {
        this.offline = offline;
        this.tokens = Map.copyOf(tokens);
        this.extraConfig = List.copyOf(extraConfig);
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
            throw new OperationException("git op must be one of " + String.join(", ", new java.util.TreeSet<>(FIELDS.keySet())));
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
            case "fetch" -> {
                a.addAll(List.of("fetch", "--no-recurse-submodules"));
                if (bool(req, "prune")) {
                    a.add("--prune");
                }
                a.add(remote(req));
                ref(a, req);
            }
            case "pull" -> {
                a.addAll(List.of("pull", "--ff-only", "--no-rebase", "--no-recurse-submodules", "--no-edit", remote(req)));
                ref(a, req);
            }
            case "push" -> {
                a.addAll(List.of("push", "--no-verify"));
                if (bool(req, "setUpstream")) {
                    a.add("--set-upstream");
                }
                a.add(remote(req));
                ref(a, req);
            }
            case "clone" -> {
                URI u = cloneUrl(req);
                a.addAll(List.of("clone", "--no-recurse-submodules", "--", u.toString(), cloneDir(req, u)));
            }
            default -> throw new AssertionError(op);
        }
        return a;
    }

    /** The remote name, {@code origin} by default (AC-01). */
    static String remote(ObjectNode req) {
        JsonNode r = req.get("remote");
        if (r == null) {
            return "origin";
        }
        if (!r.isTextual() || !r.asText().matches(REMOTE)) {
            throw new OperationException("\"remote\" must be a remote name such as origin");
        }
        return r.asText();
    }

    private static void ref(List<String> a, ObjectNode req) {
        JsonNode r = req.get("ref");
        if (r == null) {
            return;
        }
        String v = r.isTextual() ? r.asText() : "";
        if (!v.matches(REF) || v.contains("..") || v.endsWith("/") || v.endsWith(".lock") || v.contains("//")) {
            throw new OperationException("\"ref\" must be a branch or tag name such as main (no refspecs, '+' or ':')");
        }
        a.add(v);
    }

    /** An absolute {@code https://} URL with a host and no user info (AC-01). */
    static URI cloneUrl(ObjectNode req) {
        JsonNode u = req.get("url");
        if (u == null || !u.isTextual() || u.asText().length() > 2048) {
            throw new OperationException("git clone needs a \"url\" (an https:// URL, at most 2,048 characters)");
        }
        URI uri;
        try {
            uri = new URI(u.asText());
        } catch (java.net.URISyntaxException e) {
            throw new OperationException("\"url\" is not a valid URL: " + e.getReason());
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawPath() == null
                || uri.getRawPath().isEmpty()) {
            throw new OperationException("\"url\" must be an https:// URL with a host and a path (ssh and http remotes aren't allowed)");
        }
        if (uri.getRawUserInfo() != null) {
            throw new OperationException("\"url\" must not contain a user name or password; map a token in git.credentials instead");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new OperationException("\"url\" must not have a query or fragment");
        }
        return uri.normalize();
    }

    /** The new folder for a clone: {@code dir}, or the URL's last segment without {@code .git} (AC-01). */
    String cloneDir(ObjectNode req, URI u) {
        String dir;
        JsonNode d = req.get("dir");
        if (d != null) {
            if (!d.isTextual()) {
                throw new OperationException("\"dir\" must be a workspace path");
            }
            dir = d.asText();
        } else {
            String path = u.getPath().replaceAll("/+$", "");
            dir = path.substring(path.lastIndexOf('/') + 1).replaceAll("\\.git$", "");
            if (!dir.matches(DIR) || dir.equals("..")) {
                throw new OperationException("cannot name a folder after " + u + "; pass \"dir\"");
            }
        }
        Path target = workspace.resolve(dir);
        if (target.equals(workspace.root())) {
            throw new OperationException("\"dir\" must be a new folder inside the workspace, not the workspace itself");
        }
        if (java.nio.file.Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new OperationException("\"" + dir + "\" already exists; clone into a new folder");
        }
        String rel = workspace.relative(target);
        if (rel.startsWith("-")) {
            throw new OperationException("\"dir\" must not start with '-'");
        }
        return rel;
    }

    /** The workspace-relative folder a {@code clone} request will create, checked (AC-01). */
    public String cloneTarget(ObjectNode req) {
        return cloneDir(req, cloneUrl(req));
    }

    /** Whether the op reaches a remote. */
    public static boolean network(ObjectNode req) {
        return NETWORK_OPS.contains(op(req));
    }

    /**
     * One line for the approval prompt (AC-07): the remote's URL for fetch, pull and push, e.g.
     * {@code git push origin main → https://github.com/a/b.git}, which also checks it is HTTPS.
     */
    public String summary(ObjectNode req) {
        String op = op(req);
        List<String> a = args(req);
        return switch (op) {
            case "fetch", "pull", "push" -> {
                refuseOffline(op);
                String remote = remote(req);
                String ref = req.hasNonNull("ref") ? " " + req.get("ref").asText() : "";
                yield "git " + op + " " + remote + ref + " → " + remoteUrl(remote, op.equals("push"), CallTimeout.of(req, timeout, max));
            }
            case "clone" -> {
                refuseOffline(op);
                yield "git clone " + a.get(a.size() - 2) + " into " + a.get(a.size() - 1);
            }
            default -> "git " + op;
        };
    }

    private void refuseOffline(String op) {
        if (offline) {
            throw new OperationException("offline mode: git " + op + " needs the network");
        }
    }

    /** The remote's URL as git will use it (after {@code insteadOf}); it must be HTTPS (AC-03). */
    String remoteUrl(String remote, boolean push, Duration t) {
        List<String> argv = new ArrayList<>(HARDENING);
        argv.addAll(push ? List.of("remote", "get-url", "--push", remote) : List.of("remote", "get-url", remote));
        ProcessResult r = runner.run(git, argv, workspace.root(), t, env());
        if (r.exitCode() != 0) {
            throw new OperationException("no remote named \"" + remote + "\" (" + r.output().strip() + ")");
        }
        String url = r.full().strip();
        if (!url.regionMatches(true, 0, "https://", 0, 8)) {
            throw new OperationException("remote \"" + remote + "\" is " + url + "; git network operations use https:// remotes only");
        }
        return url;
    }

    /** Repo-scope keys from {@link #RISKY_QUERY} other than filters, which are overridden instead (AC-04). */
    static List<String> riskyRepoKeys(ProcessResult r) {
        if (r.exitCode() == 1 && r.full().isBlank()) {
            return List.of();
        }
        if (r.exitCode() != 0) {
            throw new OperationException("git config failed (exit " + r.exitCode() + "): " + r.output().strip());
        }
        List<String> keys = new ArrayList<>();
        for (String line : r.full().split("\n")) {
            int tab = line.indexOf('\t');
            if (tab >= 0 && REPO_SCOPES.contains(line.substring(0, tab)) && !line.substring(tab + 1).startsWith("filter.")) {
                keys.add(line.substring(tab + 1).strip());
            }
        }
        return keys;
    }

    static final List<String> RISKY_QUERY = concat(HARDENING, List.of("config", "--show-scope", "--name-only", "--get-regexp", RISKY_KEYS));

    /**
     * {@code GIT_CONFIG_*} entries for a network operation (AC-05, AC-06): the proxy with its
     * credentials, Basic proxy auth, and an {@code extraHeader} with the token for each mapped host.
     */
    List<Map.Entry<String, String>> networkConfig(ChildSandbox.ProxyRoute route) {
        List<Map.Entry<String, String>> c = new ArrayList<>();
        c.add(Map.entry("http.proxy", "http://" + route.user() + ":" + route.password() + "@127.0.0.1:" + route.port()));
        c.add(Map.entry("http.proxyAuthMethod", "basic"));
        new java.util.TreeMap<>(tokens).forEach((host, token) -> c.add(Map.entry("http.https://" + host + "/.extraHeader",
                "Authorization: Basic " + basic(token))));
        c.addAll(extraConfig);
        return c;
    }

    static String basic(String token) {
        return java.util.Base64.getEncoder().encodeToString(("x-access-token:" + token).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private ProcessResult runNetwork(String op, List<String> args, Duration t) {
        refuseOffline(op);
        ChildSandbox.ProxyRoute route = runner.sandbox().route();
        if (route == null) {
            throw new OperationException("git " + op + " needs the egress proxy, and this session has none");
        }
        Map<String, String> env = new java.util.HashMap<>(op.equals("clone") ? ENV : env());
        List<String> argv = new ArrayList<>(HARDENING);
        argv.addAll(NETWORK);
        if (!op.equals("clone")) {
            List<String> risky = riskyRepoKeys(runner.run(git, RISKY_QUERY, workspace.root(), t, env()));
            if (!risky.isEmpty()) {
                throw new OperationException("the repository's own config sets " + String.join(", ", risky)
                        + ", which git network operations don't allow; remove it from .git/config");
            }
            remoteUrl(remoteArg(args), op.equals("push"), t);
            argv.addAll(filterOverrides(repoFilters(runner.run(git, FILTER_QUERY, workspace.root(), t, env()))));
        }
        argv.addAll(args);
        env.put("GIT_ASKPASS", "");
        List<Map.Entry<String, String>> config = networkConfig(route);
        env.put("GIT_CONFIG_COUNT", Integer.toString(config.size()));
        for (int i = 0; i < config.size(); i++) {
            env.put("GIT_CONFIG_KEY_" + i, config.get(i).getKey());
            env.put("GIT_CONFIG_VALUE_" + i, config.get(i).getValue());
        }
        List<String> secrets = new ArrayList<>();
        tokens.values().forEach(tok -> {
            secrets.add(tok);
            secrets.add(basic(tok));
        });
        // Seatbelt now and then refuses the first connect to the proxy port with EPERM (about 3% of
        // runs on macOS 14). Nothing reached the proxy then, so the run is safe to repeat.
        String refused = "Failed to connect to 127.0.0.1 port " + route.port() + " ";
        ProcessResult r = null;
        for (int attempt = 1; attempt <= PROXY_CONNECT_ATTEMPTS; attempt++) {
            r = runner.run(git, argv, workspace.root(), t, Map.copyOf(env), new ChildSandbox.Grants(List.of(), true), secrets);
            if (r.exitCode() == 0 || !r.full().contains(refused) || r.timedOut()) {
                break;
            }
        }
        return r;
    }

    static final int PROXY_CONNECT_ATTEMPTS = 3;

    /** The remote name in fetch, pull or push argv: the first argument that isn't an option, after the op. */
    private static String remoteArg(List<String> args) {
        return args.stream().skip(1).filter(x -> !x.startsWith("-")).findFirst().orElse("origin");
    }

    public ProcessResult run(ObjectNode req) {
        List<String> args = args(req);
        if (NETWORK_OPS.contains(op(req))) {
            return runNetwork(op(req), args, CallTimeout.of(req, timeout, max));
        }
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
