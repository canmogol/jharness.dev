package dev.jharness.agent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import dev.jharness.security.Operation;
import dev.jharness.security.Prompter;
import dev.jharness.security.Unattended;

/**
 * {@code api.spawnAgent} (spec 026, D-064): a subagent is a fresh agent loop with its own conversation and
 * its own isolate (the {@code subagent} sandbox profile). It shares the session's files, tools, rules and
 * permission gate, narrowed by the custom agent it runs as. It can't spawn agents itself.
 */
final class SubagentRunner implements Subagents {

    static final int MAX_TASK_CHARS = 20_000;
    static final long DEFAULT_WAIT_MS = 600_000;
    static final long MAX_WAIT_MS = 3_600_000;
    /** How long {@code /stop} and closing the session wait for a stopped subagent to end. */
    static final long STOP_WAIT_MS = 5_000;

    static final String NOTE = """

            # You are a subagent

            Another agent gave you the task in the first message. It reads only your final reply, so end with a \
            reply without a code block that holds everything it needs: what you found or did, with file paths. \
            You can't start subagents yourself.
            """;

    private static final Set<String> FIELDS = Set.of("task", "agent", "model", "maxSteps", "background");

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
    private volatile String toolsText = "";
    private final AtomicInteger started = new AtomicInteger();
    private final Map<Integer, AtomicInteger> perTurn = new ConcurrentHashMap<>();
    private volatile ApiServices parentServices;
    private volatile AgentLoop parentLoop;
    private volatile String profile;
    /** Tests: a provider per subagent, by its counter (whose agent() is its name). */
    volatile java.util.function.Function<TurnCounter, ModelProvider> testProviders;

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
    void bind(ApiServices services, AgentLoop loop, String context, String projectMd, String userMd, String toolsText) {
        this.toolsText = toolsText;
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

    /** One subagent of this session: its request, its live loop and how it ended (spec 027). */
    static final class Run {
        final String name;
        final String task;
        final String customAgent;
        final String model;
        final boolean background;
        final int maxSteps;
        final long t0 = System.nanoTime();
        final CompletableFuture<String> done = new CompletableFuture<>();
        volatile AgentLoop loop;
        volatile String status = "running";
        volatile int steps;
        volatile long durationMs = -1;
        volatile String reply = "";
        volatile boolean stopped;
        volatile boolean collected;
        volatile Thread thread;
        /** Kept after the task so the user can talk to the subagent (spec 032); closed with the session or when too many are open. */
        volatile AgentIsolate isolate;
        volatile boolean open = true;
        /** The question it is waiting on for the user (spec 033), or null. */
        volatile String waiting;

        /** Wakes a question it holds, so a stop doesn't wait for the time limit. */
        volatile Runnable wake = () -> {};

        Run(String name, String task, String customAgent, String model, boolean background, int maxSteps) {
            this.name = name;
            this.task = task;
            this.customAgent = customAgent;
            this.model = model;
            this.background = background;
            this.maxSteps = maxSteps;
        }

        boolean running() {
            return status.equals("running");
        }

        /** Steps so far while it runs, else the final count. */
        int stepsSoFar() {
            AgentLoop l = loop;
            return running() && l != null ? l.stepsNow() : steps;
        }

        long elapsedMs() {
            return durationMs >= 0 ? durationMs : (System.nanoTime() - t0) / 1_000_000;
        }

        /** Stops the run: its loop now, or before its first step. */
        void stop() {
            stopped = true;
            wake.run();
            AgentLoop l = loop;
            if (l != null) {
                l.cancel();
            }
        }
    }

    /** For {@code /agents} and {@code api.agents}: one row per subagent, oldest first. */
    record Info(String name, String task, String customAgent, String model, boolean background, String status, int steps, long durationMs,
            boolean open) {}

    /** Subagents kept open for talking when idle; the oldest idle one beyond this is closed. */
    static final int MAX_OPEN = 16;
    /** Put before a message the user types to a subagent, so it knows who is asking now. */
    static final String FROM_USER = "The user is talking to you directly now (not the agent that gave you the task). They say:\n\n";

    /** The subagent the user switched to (spec 032): its questions are asked, not denied as unattended. */
    private volatile String attended;
    /** Whether someone could answer a held question (an interactive session) and settings say wait (spec 033). */
    private volatile boolean holdQuestions;
    private final Object attention = new Object();
    /** Tests: a shorter wait than settings allow. */
    volatile java.time.Duration questionWait;

    /** Interactive sessions hold background questions when {@code agent.backgroundQuestions} is wait (D-072). */
    void holdQuestions(boolean interactive) {
        holdQuestions = interactive && settings.agent().backgroundQuestions().equals("wait");
    }

    /**
     * A background subagent's question (spec 033): it waits until the user switches to the subagent, then
     * is asked; or, after {@code agent.backgroundQuestionWait} or when the subagent is stopped, refused.
     */
    private Prompter.Answer await(String agent, Operation op, Prompter real) {
        Run run = find(agent);
        if (agent.equals(attended)) {
            return real.ask(op);
        }
        run.waiting = op.summary();
        bus.publish(new dev.jharness.events.Origin(agent, parentTurns.current()), "subagent.waiting",
                Json.object().put("api", op.api()).put("summary", op.summary()));
        java.time.Duration limit = questionWait != null ? questionWait : settings.agent().backgroundQuestionWait();
        long deadline = System.nanoTime() + limit.toNanos();
        String outcome;
        try {
            synchronized (attention) {
                while (!agent.equals(attended) && !run.stopped && System.nanoTime() < deadline) {
                    attention.wait(Math.max(1, Math.min(1000, (deadline - System.nanoTime()) / 1_000_000)));
                }
            }
            outcome = agent.equals(attended) ? "asked" : run.stopped ? "stopped" : "timeout";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outcome = "stopped";
        } finally {
            run.waiting = null;
        }
        bus.publish(new dev.jharness.events.Origin(agent, parentTurns.current()), "subagent.wait_ended", Json.object().put("outcome", outcome));
        return outcome.equals("asked") ? real.ask(op) : Prompter.Answer.UNATTENDED;
    }

    private final List<Run> runs = new CopyOnWriteArrayList<>();

    @Override
    public String spawn(ObjectNode req) throws Exception {
        for (var it = req.fieldNames(); it.hasNext(); ) {
            String f = it.next();
            if (!FIELDS.contains(f)) {
                throw new IllegalArgumentException("spawnAgent does not take \"" + f + "\" (takes: agent, background, maxSteps, model, task)");
            }
        }
        String task = req.path("task").asText("").strip();
        if (task.isEmpty()) {
            throw new IllegalArgumentException("spawnAgent needs a \"task\"");
        }
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
        JsonNode bg = req.get("background");
        if (bg != null && !bg.isNull() && !bg.isBoolean()) {
            throw new IllegalArgumentException("\"background\" must be true or false");
        }
        boolean background = bg != null && bg.asBoolean(false);
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
        int running = settings.agent().subagentsRunning();
        if (background && runs.stream().filter(x -> x.background && x.running()).count() >= running) {
            throw new IllegalArgumentException(running + " background subagents are running, the most at once (agent.subagentsRunning); "
                    + "wait for one with api.waitAgent");
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
        if (background) {
            // Decisions run on the isolate's thread; marking it there makes any question UNATTENDED (AC-04).
            PermissionGate next = gate;
            gate = op -> name.equals(attended) ? next.decide(op)
                    : Unattended.run(name, holdQuestions ? this::await : null, () -> next.decide(op));
        }
        ApiServices services = new ApiServices(p.files(), gate, bus, turns, p.search(), p.git(), p.checkpoints(), p.builds(), p.http(), null, p.hooks(), p.planning(), p.mcp());
        ModelProvider provider = provider(model, def, turns, parent);
        String system = SystemPrompt.build(context, projectMd, userMd, def) + toolsText + NOTE;
        Run run = new Run(name, task, def == null ? "default" : def.name(), modelName(model, def), background, steps);
        run.wake = () -> {
            synchronized (attention) {
                attention.notifyAll();
            }
        };
        runs.add(run);
        bus.publish(turns.get(), "subagent.started", Json.object().put("task", task.length() > 2000 ? task.substring(0, 2000) + "…" : task)
                .put("customAgent", run.customAgent).put("model", run.model).put("maxSteps", steps).put("background", background));
        AgentDefinition d = def;
        if (!background) {
            run.collected = true;
            try {
                return execute(run, d, services, provider, system, turns, parent);
            } catch (RuntimeException e) {
                if (run.running()) {
                    finish(run, turns, new TurnResult("", "error", 0, String.valueOf(e.getMessage())));
                }
                throw e;
            }
        }
        Thread t = Thread.ofPlatform().name("jharness-" + name).daemon().unstarted(() -> {
            try {
                execute(run, d, services, provider, system, turns, parent);
            } catch (RuntimeException | Error e) {
                if (run.running()) {
                    finish(run, turns, new TurnResult("", "error", 0, String.valueOf(e.getMessage())));
                }
            }
        });
        run.thread = t;
        t.start();
        return Json.write(Json.object().put("agent", name).put("status", "running"));
    }

    /** Runs the subagent's loop on this thread and records how it ended. */
    private String execute(Run run, AgentDefinition def, ApiServices services, ModelProvider provider, String system, TurnCounter turns,
            AgentLoop parent) {
        TurnResult r;
        AgentIsolate isolate = new IsolateManager(settings.sandbox(), bus, turns).create(run.name, "subagent");
        run.isolate = isolate;
        try {
            if (def != null && def.helpers() != null) {
                isolate.useHelpers(def.helpers());
            }
            AgentLoop loop = new AgentLoop(provider, isolate, services, bus, turns, system, run.maxSteps, parent.resultChars());
            if (services.hooks() instanceof AgentLoop.StopHook stop) {
                loop.useStopHook(stop);
            }
            run.loop = loop;
            if (run.stopped) {
                loop.cancel();
            }
            if (!run.background) {
                parent.child(loop);
            }
            try {
                r = loop.turn(run.task, x -> {});
            } finally {
                if (!run.background) {
                    parent.child(null);
                }
            }
        } catch (RuntimeException | Error e) {
            close(run);
            throw e;
        }
        String json = finish(run, turns, r);
        closeExtra();
        return json;
    }

    private String finish(Run run, TurnCounter turns, TurnResult r) {
        String stop = run.stopped ? "stopped" : r.stopReason();
        run.steps = r.steps();
        run.durationMs = (System.nanoTime() - run.t0) / 1_000_000;
        var done = Json.object().put("stopReason", stop).put("steps", r.steps()).put("durationMs", run.durationMs);
        if (r.error() != null) {
            done.put("error", r.error());
        }
        bus.publish(turns.get(), "subagent.completed", done);
        String reply = r.reply() == null ? "" : r.reply();
        int max = parentLoop.resultChars();
        if (reply.length() > max) {
            reply = "[" + (reply.length() - max) + " characters cut]\n" + reply.substring(reply.length() - max);
        }
        run.reply = reply;
        ObjectNode out = Json.object().put("agent", run.name).put("status", stop).put("reply", reply).put("stopReason", stop).put("steps", r.steps());
        if (r.error() != null) {
            out.put("error", r.error());
        }
        String json = Json.write(out);
        run.status = stop;
        run.done.complete(json);
        return json;
    }

    private Run find(String name) {
        for (Run r : runs) {
            if (r.name.equals(name)) {
                return r;
            }
        }
        throw new IllegalArgumentException("no subagent " + name + " in this session"
                + (runs.isEmpty() ? "" : " (known: " + String.join(", ", runs.stream().map(r -> r.name).toList()) + ")"));
    }

    @Override
    public String waitAgent(ObjectNode req) throws Exception {
        for (var it = req.fieldNames(); it.hasNext(); ) {
            String f = it.next();
            if (!f.equals("agent") && !f.equals("timeoutMs")) {
                throw new IllegalArgumentException("waitAgent does not take \"" + f + "\" (takes: agent, timeoutMs)");
            }
        }
        if (!req.path("agent").isTextual()) {
            throw new IllegalArgumentException("waitAgent needs an \"agent\" name, such as sub-1");
        }
        long timeout = DEFAULT_WAIT_MS;
        JsonNode t = req.get("timeoutMs");
        if (t != null && !t.isNull()) {
            if (!t.canConvertToLong() || !t.isIntegralNumber() || t.asLong() < 0 || t.asLong() > MAX_WAIT_MS) {
                throw new IllegalArgumentException("\"timeoutMs\" must be a whole number from 0 to " + MAX_WAIT_MS);
            }
            timeout = t.asLong();
        }
        Run run = find(req.get("agent").asText());
        try {
            String json = run.done.get(timeout, TimeUnit.MILLISECONDS);
            run.collected = true;
            return json;
        } catch (TimeoutException e) {
            return Json.write(Json.object().put("agent", run.name).put("status", "running").put("steps", run.stepsSoFar()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the wait for " + run.name + " was stopped");
        }
    }

    @Override
    public String agents() {
        var list = Json.array();
        for (Info i : list()) {
            list.addObject().put("agent", i.name()).put("task", i.task()).put("customAgent", i.customAgent()).put("model", i.model())
                    .put("background", i.background()).put("status", i.status()).put("steps", i.steps()).put("durationMs", i.durationMs());
        }
        return Json.write(list);
    }

    @Override
    public String stopAgent(String name) throws Exception {
        Run run = find(name);
        if (run.running()) {
            stop(run);
        }
        return Json.write(Json.object().put("agent", run.name).put("status", run.status));
    }

    /** The session's subagents, oldest first. */
    List<Info> list() {
        List<Info> out = new ArrayList<>();
        for (Run r : runs) {
            out.add(new Info(r.name, r.task, r.customAgent, r.model, r.background, r.waiting != null && r.running() ? "waiting" : r.status,
                    r.stepsSoFar(), r.elapsedMs(), r.open));
        }
        return out;
    }

    /**
     * The user's message to a subagent (spec 032): a new turn in its own conversation, on the caller's
     * thread. The subagent must be open and not running.
     */
    TurnResult talk(String name, String message, java.util.function.Consumer<String> onDelta) {
        Run run = find(name);
        if (!run.open || run.loop == null) {
            throw new IllegalArgumentException(name + " is closed; you can't talk to it any more");
        }
        if (run.running()) {
            throw new IllegalArgumentException(name + " is still running its turn");
        }
        run.status = "running";
        run.stopped = false;
        // The user took the conversation over; the main agent isn't handed this reply.
        run.collected = true;
        AgentLoop loop = run.loop;
        loop.resume();
        TurnResult r;
        try {
            r = loop.turn(FROM_USER + message, onDelta);
        } finally {
            run.status = "done";
        }
        run.status = r.stopReason();
        run.steps += r.steps();
        run.reply = r.reply() == null ? "" : r.reply();
        return r;
    }

    /** Esc while the user talks to a subagent: stops its current turn only. */
    void interrupt(String name) {
        for (Run r : runs) {
            AgentLoop l = r.loop;
            if (r.name.equals(name) && l != null) {
                l.interrupt();
            }
        }
    }

    /** The subagent the user switched to, or null for none; only its questions reach the user (spec 032). */
    void attend(String name) {
        attended = name;
        synchronized (attention) {
            attention.notifyAll();
        }
    }

    boolean isOpen(String name) {
        return runs.stream().anyMatch(r -> r.name.equals(name) && r.open && r.loop != null);
    }

    /** Closes the oldest idle subagents beyond {@link #MAX_OPEN}; the one the user is talking to stays. */
    private synchronized void closeExtra() {
        List<Run> idle = runs.stream().filter(r -> r.open && !r.running() && !r.name.equals(attended)).toList();
        for (int i = 0; i < idle.size() - MAX_OPEN; i++) {
            close(idle.get(i));
        }
    }

    private static void close(Run run) {
        run.open = false;
        AgentIsolate i = run.isolate;
        run.isolate = null;
        if (i != null) {
            i.close();
        }
    }

    /** Session close: stops the running subagents, then closes every open one. */
    void closeAll() {
        stopAll();
        runs.forEach(SubagentRunner::close);
    }

    /** The background subagents still running. */
    List<String> running() {
        return runs.stream().filter(r -> r.background && r.running()).map(r -> r.name).toList();
    }

    /**
     * {@code /stop NAME}: stops one running subagent and waits up to {@link #STOP_WAIT_MS} for it to end.
     *
     * @return false if no running subagent has that name
     */
    boolean stop(String name) {
        for (Run r : runs) {
            if (r.name.equals(name) && r.running()) {
                stop(r);
                return true;
            }
        }
        return false;
    }

    /** {@code /stop all} and session close: stops every running subagent; returns their names. */
    List<String> stopAll() {
        List<Run> live = runs.stream().filter(Run::running).toList();
        live.forEach(Run::stop);
        live.forEach(this::await);
        return live.stream().map(r -> r.name).toList();
    }

    private void stop(Run run) {
        run.stop();
        await(run);
    }

    private void await(Run run) {
        try {
            run.done.get(STOP_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | java.util.concurrent.ExecutionException e) {
            // It ends on its own once its current call returns.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Before each turn of the main agent: the replies of background subagents that finished and weren't
     * collected with {@code waitAgent}, once each (AC-05); empty when there are none.
     */
    String drainFinished() {
        List<Run> ready = runs.stream().filter(r -> r.background && !r.running() && !r.collected).toList();
        if (ready.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder("[Background subagents that finished since your last turn]");
        var names = Json.array();
        for (Run r : ready) {
            r.collected = true;
            names.add(r.name);
            b.append("\n\n").append(r.name).append(" (").append(r.status).append(" after ").append(r.steps).append(" steps): ").append(r.reply);
        }
        bus.publish(parentTurns.get(), "subagent.delivered", Json.object().set("agents", names));
        return b.toString();
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
        var tp = testProviders;
        if (tp != null) {
            return tp.apply(turns);
        }
        String name = model != null ? model : def != null && def.model() != null ? def.model() : profile;
        if (name == null) {
            return parent.provider().forAgent(turns);
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
