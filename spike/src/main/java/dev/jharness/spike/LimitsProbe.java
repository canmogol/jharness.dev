package dev.jharness.spike;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;

/**
 * {@code spike limits}. Each check trips one limit, then builds a fresh context on the same engine
 * and runs {@code api.echo} again (AC-03, T016).
 *
 * <ul>
 *   <li>cpu: {@code while(true){}} against {@code sandbox.MaxCPUTime} (T013)
 *   <li>memory: a growing allocation against the heap and isolate memory limits (T014)
 *   <li>wallClock: the watchdog closes a context blocked in {@code api.sleep} (T015)
 *   <li>cpuWhileBlocked: blocked host time is not CPU time; host CPU work is (AC-14, T030)
 *   <li>overrides: changed values change behavior; a bad key fails start-up (AC-13, T029)
 * </ul>
 *
 * Short limits come from {@code --set} values layered on the loaded config, so the probe runs in
 * seconds; the values used are printed with each result.
 */
final class LimitsProbe implements Main.Probe {

    private static final String NAME = "limits";
    private static final String LOOP = "while (true) {}";
    private static final String ALLOCATE =
            "const keep = []; while (true) { keep.push(new Array(1 << 20).fill(1.5)); }";

    enum Kind { COMPLETED, RESOURCE_EXHAUSTED, CANCELLED, ERROR }

    record Outcome(Kind kind, double ms, String value, String message, long outputBytes) {
        Map<String, Object> json() {
            return JsonLines.obj("outcome", kind.name().toLowerCase(), "ms", ms, "value", value,
                    "message", message, "outputBytes", outputBytes);
        }
    }

    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "spike-watchdog");
        t.setDaemon(true);
        return t;
    });

    @Override
    public boolean run(List<String> args, SandboxConfig config, JsonLines out) throws Exception {
        boolean ok = cpu(config, out);
        ok &= memory(config, out);
        ok &= isolateMemory(config, out);
        ok &= wallClock(config, out);
        ok &= cpuWhileBlocked(config, out);
        ok &= overrides(config, out);
        watchdog.shutdownNow();
        return ok;
    }

    // --- T013, T016 ------------------------------------------------------------------------------

    private boolean cpu(SandboxConfig config, JsonLines out) throws Exception {
        SandboxConfig.Profile p = config.with("main.sandbox.MaxCPUTime=2s", "main.wallClock=60s").profile("main");
        try (Engine engine = Sandbox.newEngine(p)) {
            Outcome o = run(engine, p, LOOP);
            Outcome after = run(engine, p, "api.echo('hi')");
            boolean pass = o.kind == Kind.RESOURCE_EXHAUSTED && freshOk(after);
            out.write(result("cpu", pass, p, o, after));
            return pass;
        }
    }

    // --- T014, T016 ------------------------------------------------------------------------------

    private boolean memory(SandboxConfig config, JsonLines out) throws Exception {
        SandboxConfig.Profile p = config.with("main.wallClock=60s").profile("main");
        try (Engine engine = Sandbox.newEngine(p)) {
            Outcome o = run(engine, p, ALLOCATE);
            Outcome after = run(engine, p, "api.echo('hi')");
            boolean pass = (o.kind == Kind.RESOURCE_EXHAUSTED || o.kind == Kind.CANCELLED) && freshOk(after);
            out.write(result("memory", pass, p, o, after));
            return pass;
        }
    }

    /**
     * With the heap limit just below the isolate limit, records which of the two stops the
     * allocation, and whether the engine survives a full isolate heap (AC-03, T014).
     */
    private boolean isolateMemory(SandboxConfig config, JsonLines out) throws Exception {
        SandboxConfig.Profile p = config.with("main.engine.MaxIsolateMemory=512MB", "main.sandbox.MaxHeapMemory=500MB",
                "main.wallClock=60s").profile("main");
        Outcome o;
        Outcome after;
        Outcome newEngine;
        try (Engine engine = Sandbox.newEngine(p)) {
            o = run(engine, p, ALLOCATE);
            after = run(engine, p, "api.echo('hi')");
        } catch (RuntimeException e) {
            o = new Outcome(Kind.ERROR, 0, null, e.getClass().getName() + ": " + e.getMessage(), 0);
            after = o;
        }
        try (Engine engine = Sandbox.newEngine(p)) {
            newEngine = run(engine, p, "api.echo('hi')");
        }
        // Backstop: a probe-only profile with the heap limit above the isolate limit, which SandboxConfig
        // rejects, so only engine.MaxIsolateMemory can stop the allocation.
        Map<String, String> v = new java.util.LinkedHashMap<>(p.values());
        v.put("engine.MaxIsolateMemory", "256MB");
        v.put("sandbox.MaxHeapMemory", "1GB");
        SandboxConfig.Profile backstop = new SandboxConfig.Profile("probe-backstop", v);
        Outcome b;
        Outcome bAfter;
        Outcome bNewEngine;
        try (Engine engine = Sandbox.newEngine(backstop)) {
            b = run(engine, backstop, ALLOCATE);
            bAfter = run(engine, backstop, "api.echo('hi')");
        } catch (RuntimeException e) {
            b = new Outcome(Kind.ERROR, 0, null, e.getClass().getName() + ": " + e.getMessage(), 0);
            bAfter = b;
        }
        try (Engine engine = Sandbox.newEngine(backstop)) {
            bNewEngine = run(engine, backstop, "api.echo('hi')");
        }

        boolean pass = (o.kind == Kind.RESOURCE_EXHAUSTED || o.kind == Kind.CANCELLED) && freshOk(newEngine)
                && (b.kind == Kind.RESOURCE_EXHAUSTED || b.kind == Kind.CANCELLED) && freshOk(bNewEngine);
        out.write(JsonLines.obj("probe", NAME, "check", "isolateMemory", "status", pass ? "pass" : "fail",
                "limits", limits(p), "run", o.json(), "stoppedBy", stoppedBy(o),
                "freshContextSameEngine", after.json(), "freshEngine", newEngine.json(),
                "backstopLimits", limits(backstop), "backstopRun", b.json(), "backstopStoppedBy", stoppedBy(b),
                "backstopFreshContextSameEngine", bAfter.json(), "backstopFreshEngine", bNewEngine.json()));
        return pass;
    }

    private static String stoppedBy(Outcome o) {
        if (o.message == null) {
            return "none";
        }
        String m = o.message.toLowerCase();
        return m.contains("heap memory limit") ? "sandbox.MaxHeapMemory"
                // The isolate's own heap filling up: what engine.MaxIsolateMemory reports in polyglot 25.0.
                : m.contains("garbage-collected heap size exceeded") || m.contains("isolate") ? "engine.MaxIsolateMemory"
                : "other: " + o.message;
    }

    // --- T015, T016 ------------------------------------------------------------------------------

    private boolean wallClock(SandboxConfig config, JsonLines out) throws Exception {
        SandboxConfig.Profile p = config.with("main.wallClock=3s").profile("main");
        try (Engine engine = Sandbox.newEngine(p)) {
            Outcome o = run(engine, p, "api.sleep(60000); 'woke'");
            Outcome after = run(engine, p, "api.echo('hi')");
            boolean pass = o.kind == Kind.CANCELLED && o.ms < 10_000 && freshOk(after);
            out.write(result("wallClock", pass, p, o, after));
            return pass;
        }
    }

    // --- T030 (AC-14) -------------------------------------------------------------------------------

    private boolean cpuWhileBlocked(SandboxConfig config, JsonLines out) throws Exception {
        SandboxConfig.Profile p = config.with("main.sandbox.MaxCPUTime=2s", "main.wallClock=60s").profile("main");
        try (Engine engine = Sandbox.newEngine(p)) {
            Outcome blocked = run(engine, p, "api.sleep(5000); 'done'");
            Outcome busy = run(engine, p, "api.busy(5000); 'done'");
            Outcome after = run(engine, p, "api.echo('hi')");
            boolean blockedNotCounted = blocked.kind == Kind.COMPLETED && "done".equals(blocked.value);
            boolean busyCounted = busy.kind == Kind.RESOURCE_EXHAUSTED;
            boolean pass = blockedNotCounted && busyCounted && freshOk(after);
            out.write(JsonLines.obj("probe", NAME, "check", "cpuWhileBlocked", "status", pass ? "pass" : "fail",
                    "limits", limits(p),
                    "sleep5sInHost", blocked.json(), "blockedTimeCounted", !blockedNotCounted,
                    "busy5sInHost", busy.json(), "hostCpuCounted", busyCounted,
                    "freshContext", after.json()));
            return pass;
        }
    }

    // --- T029 (AC-13) -------------------------------------------------------------------------------

    private boolean overrides(SandboxConfig config, JsonLines out) throws Exception {
        boolean ok = true;

        // A lower sandbox.MaxCPUTime stops the same loop sooner.
        SandboxConfig.Profile low = config.with("main.sandbox.MaxCPUTime=1s", "main.wallClock=60s").profile("main");
        SandboxConfig.Profile high = config.with("main.sandbox.MaxCPUTime=3s", "main.wallClock=60s").profile("main");
        Outcome lowRun;
        Outcome highRun;
        try (Engine e = Sandbox.newEngine(low)) {
            lowRun = run(e, low, LOOP);
        }
        try (Engine e = Sandbox.newEngine(high)) {
            highRun = run(e, high, LOOP);
        }
        boolean cpuPass = lowRun.kind == Kind.RESOURCE_EXHAUSTED && highRun.kind == Kind.RESOURCE_EXHAUSTED
                && lowRun.ms < highRun.ms;
        out.write(JsonLines.obj("probe", NAME, "check", "overrideCpuTime", "status", cpuPass ? "pass" : "fail",
                "maxCpu1s", lowRun.json(), "maxCpu3s", highRun.json()));
        ok &= cpuPass;

        // A larger output cap lets more output through: 384KB against the 256KB default and a 512KB override.
        String print = "const line = 'x'.repeat(1023); for (let i = 0; i < 384; i++) { console.log(line); } 'printed'";
        SandboxConfig.Profile small = config.profile("main");
        SandboxConfig.Profile large = config.with("main.sandbox.MaxOutputStreamSize=512KB").profile("main");
        Outcome smallRun;
        Outcome largeRun;
        try (Engine e = Sandbox.newEngine(small)) {
            smallRun = run(e, small, print);
        }
        try (Engine e = Sandbox.newEngine(large)) {
            largeRun = run(e, large, print);
        }
        boolean outPass = smallRun.kind == Kind.RESOURCE_EXHAUSTED && largeRun.kind == Kind.COMPLETED
                && largeRun.outputBytes >= 384 * 1024;
        out.write(JsonLines.obj("probe", NAME, "check", "overrideOutputCap", "status", outPass ? "pass" : "fail",
                "cap", small.values().get("sandbox.MaxOutputStreamSize"), "capRun", smallRun.json(),
                "overrideCap", "512KB", "overrideRun", largeRun.json()));
        ok &= outPass;

        // A bad key, a removed limit value and a fixed setting all fail start-up.
        List<String> bad = List.of("main.sandbox.Bogus=1", "main.sandbox.MaxCPUTime=0s", "main.sandbox.MaxThreads=4");
        List<Object> rejected = new java.util.ArrayList<>();
        boolean badPass = true;
        for (String b : bad) {
            try {
                config.with(b);
                badPass = false;
                rejected.add(JsonLines.obj("set", b, "rejected", false));
            } catch (SandboxConfig.ConfigException e) {
                rejected.add(JsonLines.obj("set", b, "rejected", true, "error", e.getMessage()));
            }
        }
        out.write(JsonLines.obj("probe", NAME, "check", "overrideRejected", "status", badPass ? "pass" : "fail",
                "cases", rejected));
        ok &= badPass;
        return ok;
    }

    // ---------------------------------------------------------------------------------------------

    /** Runs {@code code} in a fresh context on {@code engine} under the profile's wall-clock watchdog. */
    private Outcome run(Engine engine, SandboxConfig.Profile p, String code) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        long t0 = System.nanoTime();
        Context ctx = null;
        ScheduledFuture<?> timer = null;
        try {
            ctx = Sandbox.newContext(engine, p, stdout, OutputStream.nullOutputStream());
            Context c = ctx;
            timer = watchdog.schedule(() -> c.close(true), p.wallClock().toMillis(), TimeUnit.MILLISECONDS);
            Value v = ctx.eval("js", code);
            String value = v.isString() ? v.asString() : String.valueOf(v);
            return new Outcome(Kind.COMPLETED, ms(t0), value, null, stdout.size());
        } catch (PolyglotException e) {
            Kind k = e.isResourceExhausted() ? Kind.RESOURCE_EXHAUSTED : e.isCancelled() ? Kind.CANCELLED : Kind.ERROR;
            return new Outcome(k, ms(t0), null, e.getMessage(), stdout.size());
        } catch (RuntimeException e) {
            return new Outcome(Kind.ERROR, ms(t0), null, e.getClass().getName() + ": " + e.getMessage(), stdout.size());
        } finally {
            if (timer != null) {
                timer.cancel(false);
            }
            if (ctx != null) {
                try {
                    ctx.close(true);
                } catch (RuntimeException ignored) {
                    // already closed by a limit or the watchdog
                }
            }
        }
    }

    private static boolean freshOk(Outcome after) {
        return after.kind == Kind.COMPLETED && "hi".equals(after.value);
    }

    private static Map<String, Object> result(String check, boolean pass, SandboxConfig.Profile p, Outcome o, Outcome after) {
        return JsonLines.obj("probe", NAME, "check", check, "status", pass ? "pass" : "fail",
                "limits", limits(p), "run", o.json(), "freshContext", after.json());
    }

    private static Map<String, Object> limits(SandboxConfig.Profile p) {
        Map<String, Object> m = new java.util.LinkedHashMap<>(p.values());
        m.keySet().retainAll(List.of("engine.MaxIsolateMemory", "sandbox.MaxHeapMemory", "sandbox.MaxCPUTime", "wallClock"));
        return m;
    }

    private static double ms(long t0) {
        return Math.round((System.nanoTime() - t0) / 1e5) / 10.0;
    }
}
