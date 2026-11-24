package dev.jharness.agent;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.jharness.config.ConfigException;
import dev.jharness.config.Secrets;
import dev.jharness.config.Settings;
import dev.jharness.events.EventBus;
import dev.jharness.events.Json;
import dev.jharness.llm.ModelProvider;
import dev.jharness.llm.ModelProviders;
import dev.jharness.sandbox.AgentIsolate;
import dev.jharness.sandbox.ApiServices;
import dev.jharness.sandbox.IsolateManager;
import dev.jharness.sandbox.Subagents;
import dev.jharness.security.Decision;
import dev.jharness.security.PermissionGate;

/**
 * {@code api.spawnAgent} (spec 026, D-064): a subagent is a fresh agent loop with its own conversation and
 * its own isolate (the {@code subagent} sandbox profile). It shares the session's files, tools, rules and
 * permission gate, narrowed by the custom agent it runs as. It can't spawn agents itself.
 */
final class SubagentRunner implements Subagents {

    static final int MAX_TASK_CHARS = 20_000;

    static final String NOTE = """

            # You are a subagent

            Another agent gave you the task in the first message. It reads only your final reply, so end with a \
            reply without a code block that holds everything it needs: what you found or did, with file paths. \
            You can't start subagents yourself.
            """;

    private static final Set<String> FIELDS = Set.of("task", "agent", "model", "maxSteps");

    private final Path home;
    private final Path workspace;
    private final boolean trusted;
    private final Settings settings;
    private final Secrets secrets;
    private final EventBus bus;
    private final TurnCounter parentTurns;
    private volatile String context;
    private volatile String projectMd;
    private volatile String userMd;
    private final AtomicInteger started = new AtomicInteger();
    private final Map<Integer, AtomicInteger> perTurn = new ConcurrentHashMap<>();
    private volatile ApiServices parentServices;
    private volatile AgentLoop parentLoop;
    private volatile String profile;

    SubagentRunner(Path home, Path workspace, boolean trusted, Settings settings, Secrets secrets, EventBus bus, TurnCounter parentTurns) {
        this.home = home;
        this.workspace = workspace;
        this.trusted = trusted;
        this.settings = settings;
        this.secrets = secrets;
        this.bus = bus;
        this.parentTurns = parentTurns;
    }

    /** Once the session's services and loop exist: the workspace context and memory go into each subagent's prompt. */
    void bind(ApiServices services, AgentLoop loop, String context, String projectMd, String userMd) {
        this.parentServices = services;
        this.parentLoop = loop;
        this.context = context;
        this.projectMd = projectMd;
        this.userMd = userMd;
    }

    /** The session's current model profile, so a subagent gets its own client for it; null shares the session's provider. */
    void profile(String name) {
        this.profile = name;
    }

    @Override
    public String spawn(ObjectNode req) throws Exception {
        for (var it = req.fieldNames(); it.hasNext(); ) {
            String f = it.next();
            if (!FIELDS.contains(f)) {
                throw new IllegalArgumentException("spawnAgent does not take \"" + f + "\" (takes: agent, maxSteps, model, task)");
            }
        }
        String task = req.path("task").asText("").strip();
        if (task.length() > MAX_TASK_CHARS) {
            throw new IllegalArgumentException("the task is longer than " + MAX_TASK_CHARS + " characters");
        }
        AgentDefinition def = null;
        JsonNode an = req.get("agent");
        if (an != null && !an.isNull()) {
            try {
                def = AgentDefinition.resolve(home, workspace, trusted, an.asText());
            } catch (ConfigException e) {
                throw new IllegalArgumentException(e.getMessage());
            }
        }
        String model = null;
        JsonNode mn = req.get("model");
        if (mn != null && !mn.isNull()) {
            if (!mn.isTextual() || mn.asText().isBlank()) {
                throw new IllegalArgumentException("\"model\" must be a profile name from api.models()");
            }
            model = mn.asText();
            try {
                settings.models().resolve(model);
            } catch (ConfigException e) {
                throw new IllegalArgumentException(e.getMessage());
            }
        }
        AgentLoop parent = parentLoop;
        int limit = parent.maxSteps();
        int dflt = def != null && def.maxSteps() != null ? def.maxSteps() : settings.agent().subagentMaxSteps();
        int steps = Math.min(dflt, limit);
        JsonNode ms = req.get("maxSteps");
        if (ms != null && !ms.isNull()) {
            if (!ms.isInt() || ms.asInt() < 1 || ms.asInt() > limit) {
                throw new IllegalArgumentException("\"maxSteps\" must be a whole number from 1 to " + limit + " (this session's agent.maxSteps)");
            }
            steps = ms.asInt();
        }
        int turn = parentTurns.current();
        int most = settings.agent().subagentsPerTurn();
        if (perTurn.computeIfAbsent(turn, t -> new AtomicInteger()).incrementAndGet() > most) {
            throw new IllegalArgumentException("this turn already started " + most + " subagents, the most one turn may (agent.subagentsPerTurn)");
        }
        String name = "sub-" + started.incrementAndGet();
        TurnCounter turns = TurnCounter.following(name, parentTurns);
        ApiServices p = parentServices;
        PermissionGate gate = p.gate();
        if (def != null && def.tools() != null) {
            gate = new ToolsGate(gate, def.name(), def.tools());
        }
        if (def != null && def.plan()) {
            gate = planGate(gate, def.name());
        }
        ApiServices services = new ApiServices(p.files(), gate, bus, turns, p.search(), p.git(), p.checkpoints(), p.builds(), p.http(), null);
        ModelProvider provider = provider(model, def, turns, parent);
        String system = SystemPrompt.build(context, projectMd, userMd, def) + NOTE;
        long t0 = System.nanoTime();
        bus.publish(turns.get(), "subagent.started", Json.object().put("task", task.length() > 2000 ? task.substring(0, 2000) + "…" : task)
                .put("customAgent", def == null ? "default" : def.name()).put("model", modelName(model, def)).put("maxSteps", steps));
        TurnResult r;
        try (AgentIsolate isolate = new IsolateManager(settings.sandbox(), bus, turns).create(name, "subagent")) {
            if (def != null && def.helpers() != null) {
                isolate.useHelpers(def.helpers());
            }
            AgentLoop loop = new AgentLoop(provider, isolate, services, bus, turns, system, steps, parent.resultChars());
            parent.child(loop);
            try {
                r = loop.turn(task, d -> {});
            } finally {
                parent.child(null);
            }
        }
        long ms0 = (System.nanoTime() - t0) / 1_000_000;
        var done = Json.object().put("stopReason", r.stopReason()).put("steps", r.steps()).put("durationMs", ms0);
        if (r.error() != null) {
            done.put("error", r.error());
        }
        bus.publish(turns.get(), "subagent.completed", done);
        String reply = r.reply() == null ? "" : r.reply();
        int max = parent.resultChars();
        if (reply.length() > max) {
            reply = "[" + (reply.length() - max) + " characters cut]\n" + reply.substring(reply.length() - max);
        }
        ObjectNode out = Json.object().put("agent", name).put("reply", reply).put("stopReason", r.stopReason()).put("steps", r.steps());
        if (r.error() != null) {
            out.put("error", r.error());
        }
        return Json.write(out);
    }

    @Override
    public String models() {
        ObjectNode out = Json.object();
        String current = profile != null ? profile : settings.models().defaultName();
        if (current == null) {
            out.putNull("current");
        } else {
            out.put("current", current);
        }
        var list = out.putArray("models");
        settings.models().all().values().forEach(p -> {
            ObjectNode m = list.addObject().put("name", p.name()).put("provider", p.provider()).put("model", p.model());
            if (p.notes() != null) {
                m.put("notes", p.notes());
            }
        });
        return Json.write(out);
    }

    /** The profile name the subagent runs with, for its {@code subagent.started} event. */
    private String modelName(String model, AgentDefinition def) {
        String n = model != null ? model : def != null && def.model() != null ? def.model() : profile;
        return n == null ? "session" : n;
    }

    /**
     * The request's model profile, else the custom agent's profile, else a new client for the session's profile, so the subagent's model
     * events carry its name; sessions opened without a profile (replays) share their provider.
     */
    private ModelProvider provider(String model, AgentDefinition def, TurnCounter turns, AgentLoop parent) throws ConfigException {
        String name = model != null ? model : def != null && def.model() != null ? def.model() : profile;
        if (name == null) {
            return parent.provider();
        }
        return ModelProviders.create(settings.models().resolve(name), bus, turns, secrets);
    }

    /** {@code plan: true} on the subagent's custom agent: changes are refused, as in plan mode. */
    private static PermissionGate planGate(PermissionGate next, String agent) {
        return op -> op.kind().changesState() ? Decision.deny("agent " + agent + " runs in plan mode: " + op.summary()) : next.decide(op);
    }

    static Set<String> fields() {
        return new TreeSet<>(FIELDS);
    }
}
