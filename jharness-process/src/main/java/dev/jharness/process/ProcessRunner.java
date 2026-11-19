package dev.jharness.process;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import dev.jharness.events.EventBus;
import dev.jharness.events.Json;
import dev.jharness.events.Origin;

/**
 * The only place JHarness starts a process (constitution Article IV, D-008). It takes an argv list,
 * never a command string; the executable comes from {@link Executables}; the environment is cleared
 * except for a fixed set; stdin is empty; the whole process tree is killed on timeout (D-028); and
 * every process runs inside the OS sandbox, or not at all (D-048).
 */
public final class ProcessRunner {

    /** Output kept from the start of a run for parsing and the session log. */
    public static final int FULL_OUTPUT_CHARS = 1 << 20;

    private final EventBus bus;
    private final Supplier<Origin> origin;
    private final int outputChars;
    private final ChildSandbox sandbox;

    public ProcessRunner(EventBus bus, Supplier<Origin> origin, int outputChars, ChildSandbox sandbox) {
        this.bus = bus;
        this.origin = origin;
        this.outputChars = outputChars;
        this.sandbox = java.util.Objects.requireNonNull(sandbox, "sandbox");
    }

    public ChildSandbox sandbox() {
        return sandbox;
    }

    /** Result of {@link #probe}. */
    record Probe(int exitCode, String output) {}

    /** Runs a short check (the sandbox probe) without events: argv as given, clean environment, 4 KB of output. */
    static Probe probe(List<String> argv, Path cwd, Duration timeout) {
        ProcessBuilder pb = new ProcessBuilder(argv).directory(cwd.toFile()).redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        pb.environment().clear();
        pb.environment().put("PATH", "/usr/bin:/bin:/usr/sbin:/sbin");
        try {
            Process p = pb.start();
            byte[] out = p.getInputStream().readNBytes(4096);
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                killTree(p);
                return new Probe(-1, "timed out");
            }
            return new Probe(p.exitValue(), new String(out, java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new Probe(-1, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Probe(-1, "interrupted");
        }
    }

    /**
     * @param env extra environment variables on top of the clean base
     */
    public ProcessResult run(Executable exe, List<String> args, Path cwd, Duration timeout, Map<String, String> env) {
        if (!sandbox.available()) {
            throw new OperationException("cannot run " + exe.name() + ": no OS sandbox (" + sandbox.reason() + ")");
        }
        List<String> argv = new ArrayList<>(args.size() + 1);
        argv.add(exe.path().toString());
        for (String a : args) {
            if (a == null || a.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("argument contains a NUL character");
            }
            argv.add(a);
        }
        ProcessBuilder pb = new ProcessBuilder(sandbox.wrap(argv, cwd))
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        Map<String, String> e = pb.environment();
        e.clear();
        e.put("PATH", "/usr/bin:/bin:/usr/sbin:/sbin");
        String home = System.getProperty("user.home");
        if (home != null) {
            e.put("HOME", home);
        }
        e.put("LANG", "C.UTF-8");
        e.put("LC_ALL", "C.UTF-8");
        String tmp = System.getenv("TMPDIR");
        if (tmp != null) {
            e.put("TMPDIR", tmp);
        }
        e.putAll(env);
        e.putAll(sandbox.env());

        var started = Json.object().put("executable", exe.name()).put("path", exe.path().toString())
                .put("cwd", cwd.toString()).put("sandbox", sandbox.mechanism().wire());
        var argsJson = started.putArray("args");
        args.forEach(argsJson::add);
        bus.publish(origin.get(), "process.started", started);

        long t0 = System.nanoTime();
        Process p;
        try {
            p = pb.start();
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot start " + exe.name() + ": " + ex.getMessage(), ex);
        }
        Capture capture = new Capture(outputChars);
        Thread reader = Thread.ofVirtual().start(() -> capture.drain(p));
        boolean timedOut = false;
        try {
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                timedOut = true;
                killTree(p);
            }
            reader.join(Duration.ofSeconds(5));
        } catch (InterruptedException ie) {
            killTree(p);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running " + exe.name());
        }
        int code = timedOut ? -1 : p.exitValue();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        ProcessResult r = capture.result(code, timedOut, ms);
        var done = Json.object().put("executable", exe.name()).put("exitCode", code).put("durationMs", ms)
                .put("timedOut", timedOut).put("output", r.full()).put("outputTruncated", r.fullTruncated());
        bus.publish(origin.get(), "process.completed", done);
        return r;
    }

    /** Kills every descendant first (they would be re-parented once the parent dies), then the process. */
    static void killTree(Process p) {
        List<ProcessHandle> tree = p.descendants().toList();
        tree.forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
        try {
            p.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Keeps the first {@link #FULL_OUTPUT_CHARS} characters and a sliding tail of {@code tailChars}. */
    private static final class Capture {
        private final int tailChars;
        private final StringBuilder full = new StringBuilder();
        private final StringBuilder tail = new StringBuilder();
        private long total;

        Capture(int tailChars) {
            this.tailChars = tailChars;
        }

        void drain(Process p) {
            char[] buf = new char[8192];
            try (Reader r = new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)) {
                int n;
                while ((n = r.read(buf)) > 0) {
                    synchronized (this) {
                        total += n;
                        int room = FULL_OUTPUT_CHARS - full.length();
                        if (room > 0) {
                            full.append(buf, 0, Math.min(room, n));
                        }
                        tail.append(buf, 0, n);
                        if (tail.length() > 2 * tailChars) {
                            tail.delete(0, tail.length() - tailChars);
                        }
                    }
                }
            } catch (IOException ignored) {
                // The stream closes when the process is killed.
            }
        }

        synchronized ProcessResult result(int code, boolean timedOut, long ms) {
            boolean cut = total > tailChars;
            String t = cut ? tail.substring(tail.length() - tailChars) : tail.toString();
            return new ProcessResult(code, t, cut, full.toString(), total > FULL_OUTPUT_CHARS, timedOut, ms);
        }
    }
}
