package dev.jharness.spike;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;

/**
 * {@code spike isolate [--noop | --echo] [--runs N]}.
 *
 * <ul>
 *   <li>echo: JS in an UNTRUSTED context calls {@code api.echo("hi")} (AC-02), with the effective limits (AC-13)
 *   <li>timing: engine + first context build, N runs, median (AC-04)
 *   <li>rss: resident memory for 1, 5 and 10 idle engines (AC-04)
 *   <li>memoryProtection: an engine with {@code engine.MemoryProtection=true} (AC-05)
 * </ul>
 *
 * {@code --noop} prints one line and exits; {@code --echo} runs only the echo check. Both are for
 * measuring cold start-up with hyperfine (AC-04): bare start, and start plus one isolate run.
 */
final class IsolateProbe implements Main.Probe {

    private static final String NAME = "isolate";

    @Override
    public boolean run(List<String> args, SandboxConfig config, JsonLines out) {
        if (args.contains("--noop")) {
            out.write(JsonLines.obj("probe", NAME, "check", "noop", "status", "pass"));
            return true;
        }
        int runs = intArg(args, "--runs", 20);
        SandboxConfig.Profile profile = config.profile("main");
        if (args.contains("--echo")) {
            return !Double.isNaN(echo(profile, out));
        }

        // The first isolate in the process also loads the isolate library: that is the cold number.
        double coldMs = echo(profile, out);
        boolean ok = !Double.isNaN(coldMs);
        ok &= timing(profile, runs, coldMs, out);
        ok &= rss(profile, out);
        ok &= memoryProtection(profile, out);
        return ok;
    }

    /** @return milliseconds for the whole echo run (engine, context, eval), or NaN if it failed */
    private static double echo(SandboxConfig.Profile profile, JsonLines out) {
        long t0 = System.nanoTime();
        try (Engine engine = Sandbox.newEngine(profile);
                Context ctx = Sandbox.newContext(engine, profile)) {
            String result = ctx.eval("js", "api.echo('hi')").asString();
            double elapsed = ms(System.nanoTime() - t0);
            boolean pass = "hi".equals(result);
            out.write(JsonLines.obj(
                    "probe", NAME, "check", "echo", "status", pass ? "pass" : "fail",
                    "result", result, "ms", elapsed,
                    "limits", profile.effective()));
            return pass ? elapsed : Double.NaN;
        } catch (RuntimeException e) {
            out.write(error("echo", e));
            return Double.NaN;
        }
    }

    private static boolean timing(SandboxConfig.Profile profile, int runs, double coldMs, JsonLines out) {
        double[] build = new double[runs];
        double[] firstEval = new double[runs];
        try {
            for (int i = 0; i < runs; i++) {
                long t0 = System.nanoTime();
                try (Engine engine = Sandbox.newEngine(profile);
                        Context ctx = Sandbox.newContext(engine, profile)) {
                    long t1 = System.nanoTime();
                    ctx.eval("js", "api.echo('x')");
                    long t2 = System.nanoTime();
                    build[i] = ms(t1 - t0);
                    firstEval[i] = ms(t2 - t0);
                }
            }
        } catch (RuntimeException e) {
            out.write(error("timing", e));
            return false;
        }
        out.write(JsonLines.obj(
                "probe", NAME, "check", "timing", "status", "pass", "runs", runs, "coldFirstMs", coldMs,
                "buildMs", stats(build), "buildAndFirstEvalMs", stats(firstEval)));
        return true;
    }

    private static boolean rss(SandboxConfig.Profile profile, JsonLines out) {
        if (Rss.bytes() < 0) {
            out.write(JsonLines.obj("probe", NAME, "check", "rss", "status", "skipped", "method", Rss.method()));
            return true;
        }
        List<Object> rows = new ArrayList<>();
        for (int n : new int[] {1, 5, 10}) {
            List<AutoCloseable> open = new ArrayList<>();
            try {
                long before = Rss.bytes();
                for (int i = 0; i < n; i++) {
                    Engine engine = Sandbox.newEngine(profile);
                    open.add(engine);
                    Context ctx = Sandbox.newContext(engine, profile);
                    open.add(ctx);
                    ctx.eval("js", "api.echo('x')");
                }
                long after = Rss.bytes();
                rows.add(JsonLines.obj(
                        "engines", n, "beforeMB", mb(before), "afterMB", mb(after),
                        "perIsolateMB", mb((after - before) / n)));
            } catch (RuntimeException e) {
                out.write(error("rss", e));
                return false;
            } finally {
                closeReversed(open);
            }
        }
        out.write(JsonLines.obj("probe", NAME, "check", "rss", "status", "pass", "method", Rss.method(), "rows", rows));
        return true;
    }

    /**
     * Records whether forcing memory protection works (AC-05): first as the product would build it,
     * then with experimental options allowed, since the option is experimental in polyglot 25.0.
     * An error here is a finding, not a probe failure.
     */
    private static boolean memoryProtection(SandboxConfig.Profile profile, JsonLines out) {
        List<Object> attempts = new ArrayList<>();
        for (boolean experimental : new boolean[] {false, true}) {
            Map<String, Object> a = JsonLines.obj("allowExperimentalOptions", experimental);
            try (Engine engine = Sandbox.newEngine(profile, Map.of(SandboxConfig.MEMORY_PROTECTION_OPTION, "true"), experimental);
                    Context ctx = Sandbox.newContext(engine, profile)) {
                a.put("result", ctx.eval("js", "api.echo('hi')").asString());
                a.put("outcome", "accepted; echo ran");
            } catch (RuntimeException e) {
                a.put("outcome", "error");
                a.put("error", e.getClass().getName() + ": " + e.getMessage());
            }
            attempts.add(a);
        }
        out.write(JsonLines.obj("probe", NAME, "check", "memoryProtection", "status", "recorded",
                "option", SandboxConfig.MEMORY_PROTECTION_OPTION + "=true", "cpuFlags", cpuFlags(), "attempts", attempts));
        return true;
    }

    /** Linux only: whether the CPU and kernel report protection keys ({@code pku}, {@code ospke}). */
    private static Object cpuFlags() {
        Path cpuinfo = Path.of("/proc/cpuinfo");
        if (!Files.isReadable(cpuinfo)) {
            return "n/a";
        }
        try {
            for (String l : Files.readAllLines(cpuinfo)) {
                if (l.startsWith("flags") || l.startsWith("Features")) {
                    List<String> flags = Arrays.asList(l.substring(l.indexOf(':') + 1).trim().split("\\s+"));
                    return JsonLines.obj("pku", flags.contains("pku"), "ospke", flags.contains("ospke"));
                }
            }
            return "no flags line";
        } catch (Exception e) {
            return "unreadable: " + e.getMessage();
        }
    }

    // ---------------------------------------------------------------------------------------------

    static Map<String, Object> error(String check, Exception e) {
        return JsonLines.obj("probe", NAME, "check", check, "status", "error",
                "error", e.getClass().getName() + ": " + e.getMessage());
    }

    private static Map<String, Object> stats(double[] values) {
        double[] s = values.clone();
        Arrays.sort(s);
        double median = s.length % 2 == 1 ? s[s.length / 2] : (s[s.length / 2 - 1] + s[s.length / 2]) / 2;
        return JsonLines.obj("first", round(values[0]), "median", round(median),
                "min", round(s[0]), "max", round(s[s.length - 1]));
    }

    private static void closeReversed(List<AutoCloseable> open) {
        for (int i = open.size() - 1; i >= 0; i--) {
            try {
                open.get(i).close();
            } catch (Exception ignored) {
                // best effort; the next measurement shows any leak
            }
        }
    }

    private static int intArg(List<String> args, String flag, int fallback) {
        int i = args.indexOf(flag);
        if (i < 0 || i + 1 >= args.size()) {
            return fallback;
        }
        int v = Integer.parseInt(args.get(i + 1));
        if (v < 1) {
            throw new IllegalArgumentException(flag + " must be at least 1");
        }
        return v;
    }

    private static double ms(long nanos) {
        return round(nanos / 1e6);
    }

    private static double mb(long bytes) {
        return round(bytes / (1024.0 * 1024.0));
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
