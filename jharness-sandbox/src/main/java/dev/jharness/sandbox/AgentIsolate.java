package dev.jharness.sandbox;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.SandboxPolicy;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import dev.jharness.config.SandboxSettings;
import dev.jharness.events.EventBus;
import dev.jharness.events.Json;
import dev.jharness.events.Origin;

/**
 * One agent's isolate (D-004): one {@link Engine}, a fresh {@link Context} per run, all runs on the
 * agent's own platform thread (D-015). Limits come from the sandbox profile; the policy, host
 * access and thread count are fixed here (constitution Article III).
 */
public final class AgentIsolate implements AutoCloseable {

    static final String SOURCE_NAME = "agent.js";

    /** Wraps the host object: strings in, strings out, errors become JS errors. */
    private static final String SHIM = """
            (function (h) {
              const call = (name, args) => {
                const r = JSON.parse(h[name](...args));
                if (r.error !== undefined) throw new Error(r.error);
                return r.value;
              };
              const s = (v) => (v === undefined || v === null) ? undefined : (typeof v === 'string' ? v : JSON.stringify(v));
              const t = (v) => v === undefined ? undefined : String(v);
              const api = Object.freeze({
                readContext: () => call('readContext', []),
                readFile: (path) => call('readFile', [t(path)]),
                writeFile: (path, content) => call('writeFile', [t(path), t(content)]),
                editFile: (path, oldText, newText) => call('editFile', [t(path), t(oldText), t(newText)]),
                listFiles: (q) => call('listFiles', [s(q)]),
                grep: (q) => call('grep', [s(q)]),
                git: (q) => call('git', [s(q)]),
                maven: (q) => call('maven', [s(q)]),
                gradle: (q) => call('gradle', [s(q)]),
                npm: (q) => call('npm', [s(q)]),
                http: (q) => call('http', [s(typeof q === 'string' ? {url: q} : q)]),
                fetch: (q) => call('fetch', [s(typeof q === 'string' ? {url: q} : q)]),
                emit: (e) => call('emit', [s(e)]),
              });
              const text = (v) => v === undefined ? null : (typeof v === 'string' ? v : JSON.stringify(v));
              return [api, text];
            })
            """;

    /** The text of {@code api.d.ts}, for the system prompt. */
    public static String apiDeclaration() {
        try (var in = AgentIsolate.class.getResourceAsStream("api.d.ts")) {
            if (in == null) {
                throw new IllegalStateException("api.d.ts is missing from the build");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** The shim's source, for the contract test. */
    static String shim() {
        return SHIM;
    }

    private final String agent;
    private final SandboxSettings.Profile profile;
    private final EventBus bus;
    private final Supplier<Origin> origin;
    private final Engine engine;
    private final boolean memoryProtection;
    private final ExecutorService thread;
    private volatile boolean dead;
    private volatile Context current;

    AgentIsolate(String agent, SandboxSettings.Profile profile, EventBus bus, Supplier<Origin> origin, Engine engine,
            boolean memoryProtection) {
        this.agent = agent;
        this.profile = profile;
        this.bus = bus;
        this.origin = origin;
        this.engine = engine;
        this.memoryProtection = memoryProtection;
        this.thread = Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().name("agent-" + agent).daemon().unstarted(r));
    }

    public String agent() {
        return agent;
    }

    public boolean memoryProtection() {
        return memoryProtection;
    }

    /** Esc: cancels the running block, if any; the run returns {@code cancelled} (US-2.5). */
    public void cancel() {
        Context c = current;
        if (c != null) {
            try {
                c.close(true);
            } catch (RuntimeException ignored) {
                // Closing a running context from another thread may report the cancel here.
            }
        }
    }

    /** True once the watchdog had to close the engine; the manager then builds a new isolate. */
    public boolean isDead() {
        return dead;
    }

    /** Runs one block of agent JavaScript in a fresh context. Never throws for guest problems. */
    public RunResult run(String js, ApiServices services) {
        if (dead) {
            return new RunResult("failed", null, "this agent's isolate was stopped; start a new one", 0, null, "", 0);
        }
        long t0 = System.nanoTime();
        bus.publish(origin.get(), "sandbox.run_started", Json.object().put("profile", profile.name()).put("agent", agent));
        RunClock clock = new RunClock();
        Capture out = new Capture();
        Future<RunResult> f = thread.submit(() -> execute(js, services, clock, out, t0));
        Watchdog.Outcome w;
        try {
            w = new Watchdog(profile.wallClock(), profile.engineCloseAfter(), profile.failAfter(),
                    step -> bus.publish(origin.get(), "sandbox.watchdog", Json.object().put("step", step)
                            .put("wallClockMs", profile.wallClock().toMillis())))
                    .await(f, clock, new Watchdog.Target() {
                        @Override
                        public void closeContext() {
                            Context c = current;
                            if (c != null) {
                                try {
                                    c.close(true);
                                } catch (RuntimeException ignored) {
                                    // Closing a running context from another thread may report the cancel here.
                                }
                            }
                        }

                        @Override
                        public void closeEngine() {
                            dead = true;
                            try {
                                engine.close(true);
                            } catch (RuntimeException ignored) {
                                // As above.
                            }
                        }
                    });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return finish(new RunResult("cancelled", null, "interrupted", 0, null, out.text(), ms(t0)));
        }
        RunResult r;
        if (w == Watchdog.Outcome.FAILED) {
            dead = true;
            thread.shutdownNow();
            r = new RunResult("failed", null, "the run did not stop after its wall clock (" + profile.wallClock().toSeconds()
                    + " s); the agent's isolate was stopped", 0, null, out.text(), ms(t0));
        } else {
            try {
                r = f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                r = new RunResult("cancelled", null, "interrupted", 0, null, out.text(), ms(t0));
            } catch (ExecutionException e) {
                r = new RunResult("error", null, "host error: " + e.getCause(), 0, null, out.text(), ms(t0));
            }
            if (w != Watchdog.Outcome.FINISHED && !r.status().equals("failed")) {
                r = new RunResult("cancelled", null, "stopped by the wall-clock watchdog after " + profile.wallClock().toSeconds() + " s",
                        r.line(), "wallClock", r.output(), r.durationMs());
            }
        }
        return finish(r);
    }

    private RunResult finish(RunResult r) {
        if (r.status().equals("limit")) {
            bus.publish(origin.get(), "sandbox.limit_exceeded", Json.object().put("limit", r.limit()).put("message", r.error()));
        }
        var done = Json.object().put("status", r.status()).put("durationMs", r.durationMs());
        if (r.error() != null) {
            done.put("error", r.error()).put("line", r.line());
        }
        bus.publish(origin.get(), "sandbox.run_completed", done);
        return r;
    }

    private RunResult execute(String js, ApiServices services, RunClock clock, Capture out, long t0) {
        Context ctx;
        try {
            ctx = Context.newBuilder("js")
                    .engine(engine)
                    .sandbox(SandboxPolicy.UNTRUSTED)
                    .allowHostAccess(HostAccess.UNTRUSTED)
                    .in(InputStream.nullInputStream())
                    .out(out)
                    .err(out)
                    .option("sandbox.MaxThreads", "1")
                    .options(profile.contextOptions())
                    .build();
        } catch (RuntimeException e) {
            return new RunResult("failed", null, "cannot create a context: " + e.getMessage(), 0, null, "", ms(t0));
        }
        current = ctx;
        try {
            Value pair = ctx.eval(Source.newBuilder("js", SHIM, "jharness-api.js").buildLiteral()).execute(new HostApi(services, clock));
            ctx.getBindings("js").putMember("api", pair.getArrayElement(0));
            Value text = pair.getArrayElement(1);
            Value v = ctx.eval(Source.newBuilder("js", js, SOURCE_NAME).buildLiteral());
            Value t = text.execute(v);
            return new RunResult("ok", t.isNull() ? null : t.asString(), null, 0, null, out.text(), ms(t0));
        } catch (PolyglotException e) {
            return fromPolyglot(e, out, t0, profile.values().get("sandbox.MaxCPUTime"));
        } finally {
            current = null;
            try {
                ctx.close(true);
            } catch (RuntimeException ignored) {
                // Already closed by the watchdog.
            }
        }
    }

    private static RunResult fromPolyglot(PolyglotException e, Capture out, long t0, String cpuLimit) {
        int line = agentLine(e);
        String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        if (e.isResourceExhausted()) {
            String limit = e.getMessage() == null ? null : limitName(msg);
            if (limit == null || limit.equals("unknown")) {
                // The CPU limit can trip inside the regex engine's backtracking with no message (spec 018, RUN-02).
                // Wall time is at least CPU time, so a run shorter than the limit can't have hit it.
                if (cpuLimit != null && ms(t0) >= dev.jharness.config.Units.parseDuration(cpuLimit).toMillis()) {
                    limit = "sandbox.MaxCPUTime";
                    msg = "Maximum CPU time limit of " + cpuLimit + " exceeded.";
                } else {
                    limit = "unknown";
                }
            }
            return new RunResult("limit", null, msg, line, limit, out.text(), ms(t0));
        }
        if (e.isCancelled()) {
            return new RunResult("cancelled", null, msg, line, null, out.text(), ms(t0));
        }
        if (e.isHostException()) {
            return new RunResult("error", null, "host error: " + e.asHostException(), line, null, out.text(), ms(t0));
        }
        return new RunResult("error", null, msg, line, null, out.text(), ms(t0));
    }

    private static final java.util.regex.Pattern AGENT_LINE = java.util.regex.Pattern.compile(SOURCE_NAME.replace(".", "\\.") + ":(\\d+)");

    /**
     * The line in the agent's own code where the error happened. Under the UNTRUSTED policy the host
     * stack frames carry no guest locations, so the guest error's {@code stack} (first frame in
     * {@code agent.js}, which skips the api shim) and then the message (syntax errors) are read.
     */
    static int agentLine(PolyglotException e) {
        for (PolyglotException.StackFrame f : e.getPolyglotStackTrace()) {
            if (f.getSourceLocation() != null && SOURCE_NAME.equals(f.getSourceLocation().getSource().getName())) {
                return f.getSourceLocation().getStartLine();
            }
        }
        try {
            Value g = e.getGuestObject();
            if (g != null && g.hasMembers()) {
                Value stack = g.getMember("stack");
                if (stack != null && stack.isString()) {
                    java.util.regex.Matcher m = AGENT_LINE.matcher(stack.asString());
                    if (m.find()) {
                        return Integer.parseInt(m.group(1));
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // The context may already be closed (cancelled runs).
        }
        java.util.regex.Matcher m = AGENT_LINE.matcher(String.valueOf(e.getMessage()));
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    static String limitName(String msg) {
        String m = msg.toLowerCase();
        if (m.contains("cpu time")) {
            return "sandbox.MaxCPUTime";
        }
        if (m.contains("heap memory limit")) {
            return "sandbox.MaxHeapMemory";
        }
        if (m.contains("garbage-collected heap size exceeded") || m.contains("isolate")) {
            return "engine.MaxIsolateMemory";
        }
        if (m.contains("output stream") || m.contains("error stream")) {
            return m.contains("error stream") ? "sandbox.MaxErrorStreamSize" : "sandbox.MaxOutputStreamSize";
        }
        if (m.contains("ast depth")) {
            return "sandbox.MaxASTDepth";
        }
        if (m.contains("thread")) {
            return "sandbox.MaxThreads";
        }
        return "unknown";
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    @Override
    public void close() {
        dead = true;
        thread.shutdownNow();
        try {
            engine.close(true);
        } catch (RuntimeException ignored) {
            // Already closed.
        }
    }

    /** Console output; the stream limits in the context options cap it. */
    private static final class Capture extends ByteArrayOutputStream {
        synchronized String text() {
            return toString(StandardCharsets.UTF_8);
        }
    }
}
