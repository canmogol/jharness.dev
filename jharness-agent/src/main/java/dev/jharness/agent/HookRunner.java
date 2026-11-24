package dev.jharness.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.jharness.config.Settings;
import dev.jharness.events.EventBus;
import dev.jharness.events.Json;
import dev.jharness.sandbox.AgentIsolate;
import dev.jharness.sandbox.ApiServices;
import dev.jharness.sandbox.Hooks;
import dev.jharness.sandbox.IsolateManager;
import dev.jharness.sandbox.RunResult;

/**
 * JS hooks (spec 034, D-022, D-073): {@code ~/.jharness/hooks/NAME.js} and, in a trusted project,
 * {@code .jharness/hooks/NAME.js}. A file defines any of {@code preTool(call)}, {@code postTool(call)} and
 * {@code stop(turn)}. Each hook runs in its own isolate with the subagent sandbox limits and a fresh context
 * per call; its own {@code api} calls go through the permission gate and never run hooks.
 */
final class HookRunner implements Hooks, AgentLoop.StopHook {

    static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");
    static final long MAX_FILE_BYTES = 64 * 1024;
    /** Longest call result or reply handed to a hook. */
    static final int RESULT_CHARS = 10_000;
    /** Calls kept per agent for its stop hook. */
    static final int MAX_CALLS = 200;

    /** One loaded hook. */
    record Hook(String name, String source, Path file, String code, boolean preTool, boolean postTool, boolean stop) {
        String id() {
            return source.equals("project") ? name + " (project)" : name;
        }

        List<String> functions() {
            List<String> f = new ArrayList<>();
            if (preTool) {
                f.add("preTool");
            }
            if (postTool) {
                f.add("postTool");
            }
            if (stop) {
                f.add("stop");
            }
            return f;
        }
    }

    private final EventBus bus;
    private final TurnCounter turns;
    private final List<Hook> hooks = new ArrayList<>();
    private final Map<String, String> errors = new TreeMap<>();
    private final Map<Hook, AgentIsolate> isolates = new ConcurrentHashMap<>();
    private final Map<Hook, ApiServices> hookServices = new ConcurrentHashMap<>();
    private final Map<String, ArrayNode> calls = new ConcurrentHashMap<>();
    private volatile ApiServices services;

    private HookRunner(EventBus bus, TurnCounter turns) {
        this.bus = bus;
        this.turns = turns;
    }

    static Path userFolder(Path home) {
        return home.resolve(".jharness").resolve("hooks");
    }

    static Path projectFolder(Path workspace) {
        return workspace.resolve(".jharness").resolve("hooks");
    }

    /** The project's hook files, for the trust question (read before the project is trusted). */
    static List<String> projectHooks(Path workspace) {
        return files(projectFolder(workspace)).stream().map(f -> f.getFileName().toString()).toList();
    }

    /**
     * Loads the user's hooks and, in a trusted project, the project's; each is checked in its own isolate.
     *
     * @param services what hooks' own {@code api} calls use; their hooks must be null
     */
    static HookRunner load(Path home, Path workspace, boolean trusted, Settings settings, EventBus bus, TurnCounter parent,
            ApiServices services) {
        HookRunner r = new HookRunner(bus, parent);
        r.services = services;
        r.read(userFolder(home), "user", settings, parent);
        if (trusted) {
            r.read(projectFolder(workspace), "project", settings, parent);
        }
        var loaded = Json.object();
        var names = loaded.putArray("hooks");
        for (Hook h : r.hooks) {
            var o = names.addObject().put("name", h.name()).put("source", h.source());
            var fs = o.putArray("functions");
            h.functions().forEach(fs::add);
        }
        if (!r.errors.isEmpty()) {
            var e = loaded.putObject("errors");
            r.errors.forEach(e::put);
        }
        bus.publish(parent.get(), "hooks.loaded", loaded);
        return r;
    }

    private void read(Path dir, String source, Settings settings, TurnCounter parent) {
        for (Path f : files(dir)) {
            String fn = f.getFileName().toString();
            String name = fn.substring(0, fn.length() - 3);
            String shown = (source.equals("user") ? "~/.jharness/hooks/" : ".jharness/hooks/") + fn;
            try {
                if (!NAME.matcher(name).matches()) {
                    throw new IOException("hook names use lower-case letters, digits and '-', such as guard");
                }
                if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("must be a regular file, not a link");
                }
                if (Files.size(f) > MAX_FILE_BYTES) {
                    throw new IOException("is larger than 64 KB");
                }
                if (source.equals("user") && Files.getPosixFilePermissions(f, LinkOption.NOFOLLOW_LINKS).contains(PosixFilePermission.OTHERS_WRITE)) {
                    throw new IOException("others can write it; run chmod o-w on it");
                }
                String code = Files.readString(f, StandardCharsets.UTF_8);
                TurnCounter ht = TurnCounter.following("hook-" + name, parent);
                ApiServices hs = new ApiServices(services.files(), services.gate(), bus, ht, services.search(), services.git(),
                        services.checkpoints(), services.builds(), services.http(), null, null);
                AgentIsolate iso = new IsolateManager(settings.sandbox(), bus, ht).create("hook-" + name, "subagent");
                RunResult probe = iso.run(code + "\n;[typeof preTool, typeof postTool, typeof stop].join(',')", hs);
                if (!probe.ok()) {
                    iso.close();
                    throw new IOException("doesn't run: " + (probe.error() != null ? probe.error() : probe.status()));
                }
                String[] t = probe.value().replace("\"", "").split(",");
                Hook h = new Hook(name, source, f, code, t[0].equals("function"), t[1].equals("function"), t[2].equals("function"));
                if (h.functions().isEmpty()) {
                    iso.close();
                    throw new IOException("defines none of preTool, postTool or stop");
                }
                hooks.add(h);
                isolates.put(h, iso);
                hookServices.put(h, hs);
            } catch (IOException | RuntimeException e) {
                errors.put(shown, e.getMessage());
            }
        }
    }

    private static List<Path> files(Path dir) {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".js")).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    List<Hook> hooks() {
        return List.copyOf(hooks);
    }

    Map<String, String> errors() {
        return Map.copyOf(errors);
    }

    boolean any() {
        return !hooks.isEmpty();
    }

    /** Runs {@code fn(input)} in the hook's isolate; the parsed result, or null; throws when the hook fails. */
    private JsonNode invoke(Hook h, String fn, ObjectNode input) {
        String js = h.code() + "\n;(function () { const r = " + fn + "(JSON.parse(" + Json.write(Json.MAPPER.getNodeFactory().textNode(Json.write(input)))
                + ")); return r === undefined || r === null ? 'null' : JSON.stringify(r); })()";
        long t0 = System.nanoTime();
        RunResult r = isolates.get(h).run(js, hookServices.get(h));
        if (!r.ok()) {
            throw new IllegalStateException(r.error() != null ? r.error() : r.status() + (r.limit() != null ? " (" + r.limit() + ")" : ""));
        }
        JsonNode v = Json.parse(r.value().startsWith("\"") ? Json.parse(r.value()).asText() : r.value());
        lastMs = (System.nanoTime() - t0) / 1_000_000;
        return v;
    }

    private volatile long lastMs;

    private void ran(Hook h, String event, String outcome, String reason) {
        var e = Json.object().put("hook", h.id()).put("event", event).put("outcome", outcome).put("durationMs", lastMs);
        if (reason != null) {
            e.put("reason", reason.length() > 2000 ? reason.substring(0, 2000) + "…" : reason);
        }
        bus.publish(turns.get(), "hook.ran", e);
    }

    @Override
    public String preTool(String agent, String api, String kind, String summary, ObjectNode detail) {
        ObjectNode in = Json.object().put("event", "preTool").put("agent", agent).put("api", api).put("kind", kind).put("summary", summary);
        in.set("detail", detail == null ? Json.object() : detail.deepCopy());
        for (Hook h : hooks) {
            if (!h.preTool()) {
                continue;
            }
            JsonNode r;
            try {
                lastMs = 0;
                r = invoke(h, "preTool", in);
            } catch (RuntimeException e) {
                // A guardrail that fails must not let the call through (fail closed).
                ran(h, "preTool", "error", e.getMessage());
                return h.id() + " failed (" + e.getMessage() + "), so the call was blocked";
            }
            if (r != null && r.isObject() && r.hasNonNull("block")) {
                String why = r.get("block").asText();
                ran(h, "preTool", "block", why);
                return h.id() + ": " + why;
            }
            ran(h, "preTool", "ok", null);
        }
        return null;
    }

    @Override
    public void postTool(String agent, String api, String kind, String summary, String status, String result, String error) {
        ArrayNode list = calls.computeIfAbsent(agent, a -> Json.array());
        synchronized (list) {
            if (list.size() < MAX_CALLS) {
                list.addObject().put("api", api).put("kind", kind).put("summary", summary).put("status", status);
            }
        }
        ObjectNode in = Json.object().put("event", "postTool").put("agent", agent).put("api", api).put("kind", kind).put("summary", summary)
                .put("status", status);
        if (result != null) {
            in.put("result", result.length() > RESULT_CHARS ? result.substring(0, RESULT_CHARS) + "…" : result);
        }
        if (error != null) {
            in.put("error", error);
        }
        for (Hook h : hooks) {
            if (!h.postTool()) {
                continue;
            }
            try {
                lastMs = 0;
                invoke(h, "postTool", in);
                ran(h, "postTool", "ok", null);
            } catch (RuntimeException e) {
                ran(h, "postTool", "error", e.getMessage());
            }
        }
    }

    @Override
    public String onStop(String agent, int turn, String reply, int steps, int continued) {
        ArrayNode list = calls.remove(agent);
        ObjectNode in = Json.object().put("event", "stop").put("agent", agent).put("turn", turn).put("steps", steps).put("continued", continued)
                .put("reply", reply == null ? "" : reply.length() > RESULT_CHARS ? reply.substring(reply.length() - RESULT_CHARS) : reply);
        in.set("calls", list == null ? Json.array() : list);
        for (Hook h : hooks) {
            if (!h.stop()) {
                continue;
            }
            try {
                lastMs = 0;
                JsonNode r = invoke(h, "stop", in);
                if (r != null && r.isObject() && r.hasNonNull("continue")) {
                    String m = r.get("continue").asText();
                    ran(h, "stop", "continue", m);
                    if (list != null) {
                        calls.put(agent, list);
                    }
                    return "[" + h.id() + "] " + m;
                }
                ran(h, "stop", "ok", null);
            } catch (RuntimeException e) {
                ran(h, "stop", "error", e.getMessage());
            }
        }
        return null;
    }

    void close() {
        isolates.values().forEach(AgentIsolate::close);
        isolates.clear();
    }
}
