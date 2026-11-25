package dev.jharness.sandbox;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

import org.graalvm.polyglot.HostAccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.jharness.events.EventException;
import dev.jharness.events.Json;
import dev.jharness.net.http.HttpException;
import dev.jharness.net.http.HttpOperations;
import dev.jharness.process.BuildOperations;
import dev.jharness.process.GitOperations;
import dev.jharness.process.OperationException;
import dev.jharness.process.ProcessResult;
import dev.jharness.process.Search;
import dev.jharness.security.Decision;
import dev.jharness.security.FilePolicy;
import dev.jharness.security.Operation;
import dev.jharness.security.Workspace;

/**
 * The host side of {@code api} (constitution Articles II and VI). Every method takes and returns
 * strings and never throws: it returns {@code {"value":"…"}} or {@code {"error":"…"}}, and the JS
 * shim in {@link AgentIsolate} turns errors into JS {@code Error}s. Each call resolves its paths,
 * passes the permission gate, runs, and emits {@code permission.decided} and {@code api.call}.
 */
public final class HostApi {

    static final long MAX_READ_BYTES = 1L << 20;
    static final long MAX_WRITE_BYTES = 10L << 20;

    private final ApiServices s;
    private final RunClock clock;

    HostApi(ApiServices services, RunClock clock) {
        this.s = services;
        this.clock = clock;
    }

    private interface Body {
        String run() throws Exception;
    }

    /** Asks the gate (not counted toward the wall clock), runs the body, emits events, wraps the result. */
    private String call(String api, Operation.Kind kind, String summary, ObjectNode detail, Body body) {
        Hooks hooks = s.hooks();
        String agent = s.origin().get().agent();
        if (hooks != null) {
            // Pre-tool hooks (spec 034) run in their own isolates, outside this run's clock, before the gate.
            String blocked;
            clock.pause();
            try {
                blocked = hooks.preTool(agent, api, kind.wire(), summary, detail);
            } finally {
                clock.resume();
            }
            if (blocked != null) {
                s.bus().publish(s.origin().get(), "api.call", Json.object().put("api", api).put("status", "blocked").put("summary", summary)
                        .put("error", blocked));
                post(hooks, agent, api, kind, summary, "blocked", null, blocked);
                return error("blocked by hook " + blocked);
            }
        }
        Decision d;
        clock.pause();
        try {
            d = s.gate().decide(new Operation(api, kind, summary, detail));
        } finally {
            clock.resume();
        }
        s.bus().publish(s.origin().get(), "permission.decided", Json.object().put("api", api).put("kind", kind.wire())
                .put("allowed", d.allowed()).put("reason", d.reason()).put("summary", summary));
        if (!d.allowed()) {
            s.bus().publish(s.origin().get(), "api.call", Json.object().put("api", api).put("status", "denied").put("summary", summary));
            post(hooks, agent, api, kind, summary, "denied", null, d.reason());
            return error("permission denied: " + d.reason());
        }
        try {
            String v = body.run();
            s.bus().publish(s.origin().get(), "api.call", Json.object().put("api", api).put("status", "ok").put("summary", summary));
            post(hooks, agent, api, kind, summary, "ok", v, null);
            return Json.write(Json.object().put("value", v));
        } catch (Exception e) {
            String msg = message(e);
            s.bus().publish(s.origin().get(), "api.call", Json.object().put("api", api).put("status", "error")
                    .put("summary", summary).put("error", msg));
            post(hooks, agent, api, kind, summary, "error", null, msg);
            return error(msg);
        }
    }

    private void post(Hooks hooks, String agent, String api, Operation.Kind kind, String summary, String status, String result, String err) {
        if (hooks == null) {
            return;
        }
        clock.pause();
        try {
            hooks.postTool(agent, api, kind.wire(), summary, status, result, err);
        } finally {
            clock.resume();
        }
    }

    /** For failures before the gate (bad arguments, paths outside the workspace). */
    private String reject(String api, Exception e) {
        String msg = message(e);
        s.bus().publish(s.origin().get(), "api.call", Json.object().put("api", api).put("status", "error").put("summary", api).put("error", msg));
        return error(msg);
    }

    private static String message(Exception e) {
        if (e instanceof Workspace.PathException || e instanceof OperationException || e instanceof EventException
                || e instanceof IllegalArgumentException || e instanceof HttpException || e instanceof dev.jharness.mcp.McpClient.McpException) {
            return e.getMessage();
        }
        if (e instanceof java.nio.file.NoSuchFileException n) {
            return "no such file: " + n.getFile();
        }
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    private static String error(String msg) {
        return Json.write(Json.object().put("error", msg));
    }

    private static ObjectNode args(String json) {
        if (json == null || json.isBlank() || json.equals("undefined") || json.equals("null")) {
            return Json.object();
        }
        return Json.parseObject(json);
    }

    private static String optText(ObjectNode a, String f) {
        JsonNode n = a.get(f);
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isTextual()) {
            throw new IllegalArgumentException("\"" + f + "\" must be a string");
        }
        return n.asText();
    }

    private int turn() {
        return s.origin().get().turn();
    }

    /** The file's current text for a diff, or null if it is missing, too large or not UTF-8. */
    private static String currentText(Path p) {
        try {
            if (!Files.isRegularFile(p) || Files.size(p) > MAX_READ_BYTES) {
                return null;
            }
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(p))).toString();
        } catch (IOException e) {
            return null;
        }
    }

    /** Saves a checkpoint, writes, records the result and emits {@code file.changed} (D-044). */
    private void change(Path p, String rel, String diff, byte[] bytes) throws IOException {
        if (s.checkpoints() != null) {
            s.checkpoints().beforeChange(turn(), p, rel);
        }
        Files.createDirectories(p.getParent());
        Files.write(p, bytes);
        if (s.checkpoints() != null) {
            s.checkpoints().afterChange(turn(), p);
        }
        s.bus().publish(s.origin().get(), "file.changed", Json.object().put("path", rel).put("diff", diff));
    }

    /** Drops results the deny list hides; a second line of defence behind the search exclusions. */
    private void filter(ObjectNode result, String field, java.util.function.Function<JsonNode, String> path) {
        var arr = (com.fasterxml.jackson.databind.node.ArrayNode) result.get(field);
        for (int i = arr.size() - 1; i >= 0; i--) {
            if (s.files().hidden(path.apply(arr.get(i)))) {
                arr.remove(i);
            }
        }
    }

    private static int max(ObjectNode a) {
        JsonNode n = a.get("max");
        if (n == null) {
            return Search.DEFAULT_MAX;
        }
        if (!n.canConvertToInt() || n.asInt() < 1 || n.asInt() > Search.MAX) {
            throw new IllegalArgumentException("\"max\" must be 1 to " + Search.MAX);
        }
        return n.asInt();
    }

    // ---------------------------------------------------------------------------------------------

    @HostAccess.Export
    public String readContext() {
        return call("readContext", Operation.Kind.READ, "read workspace context", Json.object(), () -> {
            ObjectNode o = Json.object();
            o.put("workspace", s.workspace().root().toString());
            o.put("os", System.getProperty("os.name"));
            o.put("arch", System.getProperty("os.arch"));
            o.put("date", LocalDate.now().toString());
            if (s.git() != null && Files.isDirectory(s.workspace().root().resolve(".git"))) {
                ProcessResult st = s.git().run(Json.object().put("op", "status"));
                if (st.exitCode() == 0) {
                    String[] lines = st.output().split("\n");
                    String branch = lines.length > 0 && lines[0].startsWith("## ") ? lines[0].substring(3) : "";
                    branch = branch.replaceFirst("^No commits yet on ", "");
                    int dots = branch.indexOf("...");
                    branch = dots >= 0 ? branch.substring(0, dots) : branch.replaceFirst(" .*", "");
                    boolean dirty = java.util.Arrays.stream(lines).skip(1).anyMatch(l -> !l.isBlank());
                    o.putObject("git").put("branch", branch).put("dirty", dirty);
                } else {
                    o.putNull("git");
                }
            } else {
                o.putNull("git");
            }
            var mounts = o.putArray("mounts");
            s.files().mounts().forEach(m -> mounts.add(m.toString()));
            var files = o.putArray("files");
            try (Stream<Path> st = Files.list(s.workspace().root())) {
                st.filter(p -> !s.files().hidden(p.getFileName().toString()))
                        .map(p -> p.getFileName().toString() + (Files.isDirectory(p) ? "/" : "")).sorted().limit(200).forEach(files::add);
            }
            return Json.write(o);
        });
    }

    @HostAccess.Export
    public String readFile(String path) {
        Path p;
        String rel;
        try {
            FilePolicy.Target t = s.files().read(path);
            p = t.path();
            rel = t.display();
        } catch (RuntimeException e) {
            return reject("readFile", e);
        }
        return call("readFile", Operation.Kind.READ, "read " + rel, Json.object().put("path", rel), () -> {
            if (!Files.isRegularFile(p)) {
                throw new IllegalArgumentException(Files.isDirectory(p) ? rel + " is a folder" : "no such file: " + rel);
            }
            long size = Files.size(p);
            if (size > MAX_READ_BYTES) {
                throw new IllegalArgumentException(rel + " is " + size + " bytes; readFile reads up to " + MAX_READ_BYTES);
            }
            try {
                return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(p))).toString();
            } catch (CharacterCodingException e) {
                throw new IllegalArgumentException(rel + " is not UTF-8 text");
            }
        });
    }

    @HostAccess.Export
    public String writeFile(String path, String content) {
        Path p;
        String rel;
        try {
            FilePolicy.Target t = s.files().write(path);
            p = t.path();
            rel = t.display();
            if (content == null) {
                throw new IllegalArgumentException("content is missing");
            }
        } catch (RuntimeException e) {
            return reject("writeFile", e);
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        boolean existed = Files.exists(p);
        String diff = Diffs.unified(rel, existed ? currentText(p) : null, content);
        return call("writeFile", Operation.Kind.WRITE, "write " + rel + " (" + bytes.length + " bytes)",
                Json.object().put("path", rel).put("bytes", bytes.length).put("diff", diff), () -> {
                    if (bytes.length > MAX_WRITE_BYTES) {
                        throw new IllegalArgumentException("content is " + bytes.length + " bytes; the limit is " + MAX_WRITE_BYTES);
                    }
                    if (Files.isDirectory(p)) {
                        throw new IllegalArgumentException(rel + " is a folder");
                    }
                    change(p, rel, diff, bytes);
                    return Json.write(Json.object().put("path", rel).put("bytes", bytes.length));
                });
    }

    @HostAccess.Export
    public String editFile(String path, String oldText, String newText) {
        Path p;
        String rel;
        try {
            FilePolicy.Target t = s.files().write(path);
            p = t.path();
            rel = t.display();
            if (oldText == null || oldText.isEmpty() || newText == null) {
                throw new IllegalArgumentException("editFile needs a non-empty oldText and a newText");
            }
        } catch (RuntimeException e) {
            return reject("editFile", e);
        }
        String before = currentText(p);
        String diff = before == null || before.indexOf(oldText) < 0 ? ""
                : Diffs.unified(rel, before, before.replaceFirst(java.util.regex.Pattern.quote(oldText), java.util.regex.Matcher.quoteReplacement(newText)));
        return call("editFile", Operation.Kind.WRITE, "edit " + rel, Json.object().put("path", rel).put("diff", diff), () -> {
            if (!Files.isRegularFile(p)) {
                throw new IllegalArgumentException("no such file: " + rel);
            }
            if (Files.size(p) > MAX_WRITE_BYTES) {
                throw new IllegalArgumentException(rel + " is too large to edit");
            }
            String text = Files.readString(p, StandardCharsets.UTF_8);
            int first = text.indexOf(oldText);
            if (first < 0) {
                throw new IllegalArgumentException("oldText was not found in " + rel);
            }
            if (text.indexOf(oldText, first + 1) >= 0) {
                int n = 0;
                for (int i = text.indexOf(oldText); i >= 0; i = text.indexOf(oldText, i + 1)) {
                    n++;
                }
                throw new IllegalArgumentException("oldText occurs " + n + " times in " + rel + "; include more context so it occurs once");
            }
            String after = text.substring(0, first) + newText + text.substring(first + oldText.length());
            change(p, rel, Diffs.unified(rel, text, after), after.getBytes(StandardCharsets.UTF_8));
            return Json.write(Json.object().put("path", rel).put("replacements", 1));
        });
    }

    @HostAccess.Export
    public String listFiles(String json) {
        ObjectNode a;
        Path dir;
        String rel;
        int max;
        String glob;
        try {
            a = args(json);
            FilePolicy.Target t = s.files().read(a.has("path") ? optText(a, "path") : ".");
            dir = t.path();
            rel = t.display();
            glob = optText(a, "glob");
            max = max(a);
        } catch (RuntimeException e) {
            return reject("listFiles", e);
        }
        return call("listFiles", Operation.Kind.READ, "list " + rel + (glob == null ? "" : " " + glob), a, () -> {
            if (!Files.isDirectory(dir)) {
                throw new IllegalArgumentException(rel + " is not a folder");
            }
            ObjectNode r = s.search().listFiles(dir, glob, max);
            filter(r, "files", n -> n.asText());
            return Json.write(r);
        });
    }

    @HostAccess.Export
    public String grep(String json) {
        ObjectNode a;
        Path dir;
        String rel;
        String pattern;
        String glob;
        int max;
        boolean ignoreCase;
        try {
            a = args(json);
            pattern = optText(a, "pattern");
            if (pattern == null || pattern.isEmpty()) {
                throw new IllegalArgumentException("grep needs a \"pattern\"");
            }
            FilePolicy.Target t = s.files().read(a.has("path") ? optText(a, "path") : ".");
            dir = t.path();
            rel = t.display();
            glob = optText(a, "glob");
            max = max(a);
            JsonNode ic = a.get("ignoreCase");
            if (ic != null && !ic.isBoolean()) {
                throw new IllegalArgumentException("\"ignoreCase\" must be true or false");
            }
            ignoreCase = ic != null && ic.asBoolean();
        } catch (RuntimeException e) {
            return reject("grep", e);
        }
        return call("grep", Operation.Kind.READ, "grep " + pattern + " in " + rel, a, () -> {
            ObjectNode r = s.search().grep(pattern, dir, glob, ignoreCase, max);
            filter(r, "matches", n -> n.path("path").asText());
            return Json.write(r);
        });
    }

    @HostAccess.Export
    public String git(String json) {
        ObjectNode a;
        Operation.Kind kind;
        String summary;
        try {
            if (s.git() == null) {
                throw new IllegalArgumentException("git is not installed");
            }
            a = args(json);
            kind = GitOperations.kind(a);
            if (a.path("op").asText().equals("clone")) {
                // The new folder must pass the file policy like any write (spec 017).
                s.files().write(s.git().cloneTarget(a));
            }
            // For fetch, pull and push this reads the remote's URL, and refuses non-HTTPS remotes and offline mode.
            summary = s.git().summary(a);
            for (JsonNode p : a.path("paths")) {
                // add and restore change files; every path must pass the file policy.
                if (kind == Operation.Kind.GIT_WRITE) {
                    s.files().write(p.asText());
                } else {
                    s.files().read(p.asText());
                }
            }
        } catch (RuntimeException e) {
            return reject("git", e);
        }
        String op = a.path("op").asText();
        return call("git", kind, summary, a, () -> {
            List<Path> restored = new java.util.ArrayList<>();
            if (s.checkpoints() != null && kind == Operation.Kind.GIT_WRITE) {
                s.checkpoints().touchedGit(turn(), op);
                if (op.equals("restore") && !a.path("staged").asBoolean(false)) {
                    for (JsonNode p : a.path("paths")) {
                        FilePolicy.Target t = s.files().write(p.asText());
                        if (Files.isRegularFile(t.path())) {
                            s.checkpoints().beforeChange(turn(), t.path(), t.display());
                            restored.add(t.path());
                        }
                    }
                }
            }
            String r = GitOperations.toJson(outsideRunClock(() -> s.git().run(a)));
            restored.forEach(p -> s.checkpoints().afterChange(turn(), p));
            return r;
        });
    }

    /** {@code api.tool(name, request)} (spec 028, D-067): checked first, then approved, then run in the OS sandbox. */
    @HostAccess.Export
    public String tool(String name, String json) {
        ObjectNode a;
        ObjectNode detail;
        String summary;
        String tool = name == null ? "" : name;
        try {
            if (s.builds() == null) {
                throw new IllegalArgumentException("tools are not available in this session");
            }
            if (tool.isBlank()) {
                throw new IllegalArgumentException("api.tool needs a tool name first, such as api.tool(\"maven\", {op: \"test\"}); api.tools() lists them");
            }
            a = json != null && !json.startsWith("{") ? Json.object().put("op", json) : args(json);
            s.builds().check(tool, a);
            detail = s.builds().detail(tool, a);
            summary = s.builds().summary(tool, a);
        } catch (RuntimeException e) {
            return reject("tool", e);
        }
        return call("tool", Operation.Kind.BUILD, summary, detail,
                () -> BuildOperations.toJson(outsideRunClock(() -> s.builds().run(tool, a))));
    }

    /** {@code api.tools()} (spec 028, D-067): the defined tools, their operations and fields, and whether each can run here. */
    @HostAccess.Export
    public String tools() {
        return call("tools", Operation.Kind.READ, "list tools", Json.object(), () -> s.builds() == null ? "[]" : s.builds().list());
    }

    /** Process runs have their own timeout (D-028), so they don't count against the run's wall clock. */
    private <T> T outsideRunClock(java.util.function.Supplier<T> body) {
        clock.pause();
        try {
            return body.get();
        } finally {
            clock.resume();
        }
    }

    /** {@code api.http} (spec 016): GET and HEAD need only the network rules; other methods ask. */
    @HostAccess.Export
    public String http(String json) {
        HttpOperations.Request r;
        try {
            if (s.http() == null) {
                throw new IllegalArgumentException("http is not available in this session");
            }
            r = s.http().check(args(json));
        } catch (RuntimeException e) {
            return reject("http", e);
        }
        return call("http", r.kind(), r.summary(), r.detail(), () -> Json.write(outsideRunClock(() -> s.http().http(r))));
    }

    /** {@code api.fetch} (spec 016): a GET whose page comes back as Markdown-like text. */
    @HostAccess.Export
    public String fetch(String json) {
        HttpOperations.Request r;
        try {
            if (s.http() == null) {
                throw new IllegalArgumentException("fetch is not available in this session");
            }
            r = s.http().checkFetch(args(json));
        } catch (RuntimeException e) {
            return reject("fetch", e);
        }
        return call("fetch", r.kind(), "fetch " + r.uri(), r.detail(), () -> Json.write(outsideRunClock(() -> s.http().fetch(r))));
    }

    /** {@code api.spawnAgent} (spec 026): runs a subagent on a task; its own api calls ask as usual. */
    @HostAccess.Export
    public String spawnAgent(String json) {
        ObjectNode a;
        String summary;
        try {
            if (s.subagents() == null) {
                throw new IllegalArgumentException("spawnAgent is not available here (a subagent can't start other agents)");
            }
            a = args(json);
            String task = optText(a, "task");
            if (task == null || task.isBlank()) {
                throw new IllegalArgumentException("spawnAgent needs a \"task\"");
            }
            String agent = optText(a, "agent");
            String model = optText(a, "model");
            String t = task.strip().replaceAll("\\s+", " ");
            summary = "spawn " + (agent != null ? "agent " + agent : "a subagent") + (model != null ? " on " + model : "") + ": "
                    + (t.length() > 80 ? t.substring(0, 80) + "…" : t);
        } catch (RuntimeException e) {
            return reject("spawnAgent", e);
        }
        ObjectNode detail = Json.object().put("agent", a.path("agent").asText("default"));
        if (a.hasNonNull("model")) {
            detail.put("model", a.path("model").asText());
        }
        return call("spawnAgent", Operation.Kind.AGENT, summary, detail, () -> {
            try {
                return outsideRunClock(() -> {
                    try {
                        return s.subagents().spawn(a);
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
            } catch (IllegalStateException e) {
                throw e.getCause() instanceof Exception x ? x : e;
            }
        });
    }

    /** {@code api.models} (spec 026): the model profiles {@code spawnAgent} can run a subagent with. */
    @HostAccess.Export
    public String models() {
        if (s.subagents() == null) {
            return reject("models", new IllegalArgumentException("models is not available here (a subagent can't start other agents)"));
        }
        return call("models", Operation.Kind.READ, "list model profiles", Json.object(), () -> s.subagents().models());
    }

    /** {@code api.agents} (spec 027): this session's subagents. */
    @HostAccess.Export
    public String agents() {
        if (s.subagents() == null) {
            return reject("agents", new IllegalArgumentException("agents is not available here (a subagent can't start other agents)"));
        }
        return call("agents", Operation.Kind.READ, "list subagents", Json.object(), () -> s.subagents().agents());
    }

    /** {@code api.stopAgent} (spec 027): stops a running background subagent. */
    @HostAccess.Export
    public String stopAgent(String name) {
        if (s.subagents() == null) {
            return reject("stopAgent", new IllegalArgumentException("stopAgent is not available here (a subagent can't start other agents)"));
        }
        if (name == null || name.isBlank()) {
            return reject("stopAgent", new IllegalArgumentException("stopAgent needs a subagent name, such as sub-1"));
        }
        return call("stopAgent", Operation.Kind.AGENT, "stop " + name, Json.object().put("agent", name), () -> agentCall(() -> s.subagents().stopAgent(name)));
    }

    private interface AgentCall {
        String run() throws Exception;
    }

    /** Runs a subagent call outside the run clock, passing its own exceptions through. */
    private String agentCall(AgentCall c) throws Exception {
        try {
            return outsideRunClock(() -> {
                try {
                    return c.run();
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        } catch (IllegalStateException e) {
            throw e.getCause() instanceof Exception x ? x : e;
        }
    }

    /**
     * {@code api.mcp(request)} (spec 037, D-023, D-076): {@code {server, tool, input, timeoutMs}} calls a tool
     * after approval; {@code {list: true}} or {@code {server, list: true}} lists the tools without asking.
     */
    @HostAccess.Export
    public String mcp(String json) {
        ObjectNode a;
        try {
            if (s.mcp() == null || !s.mcp().any()) {
                throw new IllegalArgumentException("no MCP servers are set up (add them to \"mcp\" in ~/.jharness/settings.json)");
            }
            a = args(json);
            for (var it = a.fieldNames(); it.hasNext(); ) {
                String f = it.next();
                if (!java.util.Set.of("server", "tool", "input", "timeoutMs", "list").contains(f)) {
                    throw new IllegalArgumentException("api.mcp does not take \"" + f + "\" (takes: input, list, server, timeoutMs, tool)");
                }
            }
        } catch (RuntimeException e) {
            return reject("mcp", e);
        }
        String server = optText(a, "server");
        String tool = optText(a, "tool");
        if (a.path("list").asBoolean(false) || tool == null) {
            if (tool == null && !a.path("list").asBoolean(false)) {
                return reject("mcp", new IllegalArgumentException("api.mcp needs a \"tool\", or {list: true} to see the tools"));
            }
            try {
                s.mcp().list(server);
            } catch (RuntimeException e) {
                return reject("mcp", e);
            }
            return call("mcp", Operation.Kind.READ, "list MCP tools" + (server == null ? "" : " of " + server), Json.object(),
                    () -> Json.write(s.mcp().list(server)));
        }
        java.time.Duration timeout;
        JsonNode input = a.get("input");
        try {
            if (server == null) {
                throw new IllegalArgumentException("api.mcp needs a \"server\"");
            }
            if (input != null && !input.isNull() && !input.isObject()) {
                throw new IllegalArgumentException("\"input\" must be an object of the tool's arguments");
            }
            s.mcp().check(server, tool);
            timeout = a.has("timeoutMs") ? java.time.Duration.ofMillis(timeoutMs(a)) : null;
        } catch (RuntimeException e) {
            return reject("mcp", e);
        }
        String shown = input == null || input.isNull() ? "" : Json.write(input);
        String summary = "mcp " + server + "/" + tool + (shown.isEmpty() || shown.equals("{}") ? "" : " " + (shown.length() > 80 ? shown.substring(0, 80) + "…" : shown));
        ObjectNode detail = Json.object().put("server", server).put("tool", tool).put("op", server + "/" + tool);
        detail.set("input", input == null ? Json.object() : input);
        return call("mcp", Operation.Kind.MCP, summary, detail, () -> agentCall(() -> Json.write(s.mcp().call(server, tool, input, timeout))));
    }

    private static long timeoutMs(ObjectNode a) {
        JsonNode t = a.get("timeoutMs");
        if (!t.isIntegralNumber() || t.asLong() < 1 || t.asLong() > 600_000) {
            throw new IllegalArgumentException("\"timeoutMs\" must be a whole number from 1 to 600000");
        }
        return t.asLong();
    }

    /** {@code api.todos(list?)} (spec 036): sets or reads this agent's todo list. */
    @HostAccess.Export
    public String todos(String json) {
        if (s.planning() == null) {
            return reject("todos", new IllegalArgumentException("todos is not available in this session"));
        }
        String agent = s.origin().get().agent();
        return call("todos", Operation.Kind.SESSION, json == null ? "read the todo list" : "update the todo list", Json.object(),
                () -> s.planning().todos(agent, json));
    }

    /** {@code api.proposePlan(text)} (spec 036): in plan mode, asks the user to approve the plan. */
    @HostAccess.Export
    public String proposePlan(String text) {
        if (s.planning() == null) {
            return reject("proposePlan", new IllegalArgumentException("proposePlan is not available here (only the main agent plans)"));
        }
        if (text == null || text.isBlank()) {
            return reject("proposePlan", new IllegalArgumentException("proposePlan needs the plan as text"));
        }
        String agent = s.origin().get().agent();
        return call("proposePlan", Operation.Kind.SESSION, "propose a plan", Json.object(),
                () -> outsideRunClock(() -> s.planning().proposePlan(agent, text)));
    }

    @HostAccess.Export
    public String emit(String json) {
        return call("emit", Operation.Kind.EMIT, "emit event", Json.object(),
                () -> s.bus().publishGuest(s.origin().get(), json).id());
    }
}
