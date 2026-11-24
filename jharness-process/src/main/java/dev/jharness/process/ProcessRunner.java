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
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import dev.jharness.events.EventBus;
import dev.jharness.events.Json;
import dev.jharness.events.Origin;

/**
 * The only place JHarness starts a process (constitution Article IV, D-008). It takes an argv list,
 * never a command string; the executable comes from {@link Executables}; the environment is cleared
 * except for named variables; the executable must still be the file it resolved to; argument sizes are capped;
 * stdin is empty; the whole process tree is killed on timeout (D-028); and
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
        return run(exe, args, cwd, timeout, env, ChildSandbox.Grants.NONE);
    }

    /**
     * @param grants cache folders and proxy network for build tools (D-049); {@link ChildSandbox.Grants#NONE} otherwise
     */
    public ProcessResult run(Executable exe, List<String> args, Path cwd, Duration timeout, Map<String, String> env,
            ChildSandbox.Grants grants) {
        return run(exe, args, cwd, timeout, env, grants, List.of());
    }

    /**
     * @param secrets values passed in {@code env} that must not show in the output or events, such as
     *     git tokens (spec 017); the proxy password is always redacted
     */
    public ProcessResult run(Executable exe, List<String> args, Path cwd, Duration timeout, Map<String, String> env,
            ChildSandbox.Grants grants, List<String> secrets) {
        if (!sandbox.available()) {
            throw new OperationException("cannot run " + exe.name() + ": no OS sandbox (" + sandbox.reason() + ")");
        }
        checkArgs(args);
        checkEnv(env);
        try {
            exe.verify();
        } catch (OperationException ex) {
            bus.publish(origin.get(), "process.refused", Json.object().put("executable", exe.name())
                    .put("path", exe.path().toString()).put("reason", ex.getMessage()));
            throw ex;
        }
        List<String> argv = new ArrayList<>(args.size() + 1);
        argv.add(exe.path().toString());
        argv.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(sandbox.wrap(argv, cwd, grants))
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
        e.putAll(env);
        e.putAll(sandbox.env());

        var started = Json.object().put("executable", exe.name()).put("path", exe.path().toString())
                .put("cwd", cwd.toString()).put("sandbox", sandbox.mechanism().wire()).put("network", grants.network())
                .put("loopback", grants.loopback());
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
        ChildSandbox.ProxyRoute route = sandbox.route();
        List<String> hidden = new ArrayList<>(secrets);
        if (route != null) {
            hidden.add(route.password());
        }
        Capture capture = new Capture(outputChars, hidden);
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

    /**
     * A long-running child with its stdin and stdout as pipes, such as a stdio MCP server (spec 037, D-076);
     * stderr keeps its last 4 KB for error messages.
     */
    public final class Live implements AutoCloseable {
        private final Process process;
        private final String name;
        private final StringBuilder err = new StringBuilder();
        private final long t0 = System.nanoTime();

        Live(Process process, String name) {
            this.process = process;
            this.name = name;
            Thread.ofPlatform().daemon().name("stderr-" + name).start(() -> {
                try (var in = process.getErrorStream()) {
                    byte[] b = new byte[1024];
                    int n;
                    while ((n = in.read(b)) > 0) {
                        synchronized (err) {
                            err.append(new String(b, 0, n, StandardCharsets.UTF_8));
                            if (err.length() > 4096) {
                                err.delete(0, err.length() - 4096);
                            }
                        }
                    }
                } catch (IOException e) {
                    // The process ended.
                }
            });
        }

        public java.io.OutputStream stdin() {
            return process.getOutputStream();
        }

        public java.io.InputStream stdout() {
            return process.getInputStream();
        }

        public boolean alive() {
            return process.isAlive();
        }

        /** The last 4 KB the process wrote to stderr. */
        public String stderr() {
            synchronized (err) {
                return err.toString();
            }
        }

        @Override
        public void close() {
            if (process.isAlive()) {
                killTree(process);
            }
            bus.publish(origin.get(), "process.completed", Json.object().put("executable", name)
                    .put("exitCode", process.isAlive() ? -1 : process.exitValue()).put("durationMs", (System.nanoTime() - t0) / 1_000_000)
                    .put("timedOut", false).put("output", "").put("outputTruncated", false));
        }
    }

    /** Starts {@code exe} in the OS sandbox with pipes, for a process that keeps running (spec 037). */
    public Live start(Executable exe, List<String> args, Path cwd, Map<String, String> env, ChildSandbox.Grants grants) {
        if (!sandbox.available()) {
            throw new OperationException("cannot run " + exe.name() + ": no OS sandbox (" + sandbox.reason() + ")");
        }
        checkArgs(args);
        checkEnv(env);
        exe.verify();
        List<String> argv = new ArrayList<>(args.size() + 1);
        argv.add(exe.path().toString());
        argv.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(sandbox.wrap(argv, cwd, grants)).directory(cwd.toFile());
        Map<String, String> e = pb.environment();
        e.clear();
        e.put("PATH", "/usr/bin:/bin:/usr/sbin:/sbin");
        String home = System.getProperty("user.home");
        if (home != null) {
            e.put("HOME", home);
        }
        e.put("LANG", "C.UTF-8");
        e.put("LC_ALL", "C.UTF-8");
        e.putAll(env);
        e.putAll(sandbox.env());
        var started = Json.object().put("executable", exe.name()).put("path", exe.path().toString())
                .put("cwd", cwd.toString()).put("sandbox", sandbox.mechanism().wire()).put("network", grants.network())
                .put("loopback", grants.loopback());
        var argsJson = started.putArray("args");
        args.forEach(argsJson::add);
        bus.publish(origin.get(), "process.started", started);
        try {
            return new Live(pb.start(), exe.name());
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot start " + exe.name() + ": " + ex.getMessage(), ex);
        }
    }

    /** Runs of a process whose proxy connection the sandbox refused, first run included (spec 019, D-057). */
    public static final int PROXY_ATTEMPTS = 3;

    /** A retry starts only if at least this much of the call's timeout is left. */
    static final Duration MIN_RETRY_TIME = Duration.ofSeconds(1);

    /** One run of the process with the time it may take. */
    @FunctionalInterface
    public interface Attempt {
        ProcessResult run(Duration timeout);
    }

    /**
     * Runs {@code attempt} and runs it again, up to {@link #PROXY_ATTEMPTS} times in all, while it fails
     * and {@code refused} says the sandbox refused its connection to the proxy port. Seatbelt does this
     * now and then on macOS (spec 019). All runs share {@code timeout}; a run that timed out or succeeded
     * is never repeated. Each retry emits {@code process.retried}.
     */
    public ProcessResult retryingProxyRefusals(String executable, Duration timeout,
            java.util.function.Predicate<ProcessResult> refused, Attempt attempt) {
        long deadline = System.nanoTime() + timeout.toNanos();
        ProcessResult r = attempt.run(timeout);
        for (int n = 2; n <= PROXY_ATTEMPTS; n++) {
            if (r.exitCode() == 0 || r.timedOut() || !refused.test(r)) {
                break;
            }
            Duration left = Duration.ofNanos(deadline - System.nanoTime());
            if (left.compareTo(MIN_RETRY_TIME) < 0) {
                break;
            }
            bus.publish(origin.get(), "process.retried", Json.object().put("executable", executable).put("attempt", n)
                    .put("attempts", PROXY_ATTEMPTS).put("reason", "proxy-connect-refused").put("remainingMs", left.toMillis()));
            r = attempt.run(left);
        }
        return r;
    }

    /**
     * Whether a build tool's output shows the sandbox refusing its connection to the proxy port: one line
     * with {@code EPERM} or "Operation not permitted" and {@code 127.0.0.1:<port>}, {@code localhost:<port>}
     * or {@code 127.0.0.1 port <port>}. npm prints {@code connect EPERM 127.0.0.1:<port>}; Maven and Gradle
     * print {@code Connect to 127.0.0.1:<port> [/127.0.0.1] failed: Operation not permitted}.
     */
    public static boolean proxyRefused(String output, int port) {
        java.util.regex.Pattern at = java.util.regex.Pattern.compile("(?:127\\.0\\.0\\.1|localhost)(?::| port )" + port + "(?![0-9])");
        for (String line : output.split("\n")) {
            if (PERMISSION.matcher(line).find() && at.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    private static final java.util.regex.Pattern PERMISSION =
            java.util.regex.Pattern.compile("EPERM|operation not permitted", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Argument limits (spec 015, AC-07); Linux caps one argument at 128 KiB. */
    static final int MAX_ARGS = 4096;
    static final int MAX_ARG_BYTES = 131_072;
    static final int MAX_TOTAL_BYTES = 1 << 20;

    /**
     * The only variables a caller may add to the clean base of {@code PATH}, {@code HOME}, {@code LANG},
     * {@code LC_ALL} and the sandbox's {@code TMPDIR} (spec 015, AC-06).
     */
    static final Set<String> ENV_NAMES = Set.of(
            "PATH", "JAVA_HOME", "JAVA_TOOL_OPTIONS",
            "GIT_CONFIG_NOSYSTEM", "GIT_TERMINAL_PROMPT", "GIT_OPTIONAL_LOCKS", "GIT_WORK_TREE", "GIT_ASKPASS", "GIT_CONFIG_COUNT",
            "npm_config_offline", "npm_config_proxy", "npm_config_https_proxy", "npm_config_update_notifier", "npm_config_fund",
            "npm_config_audit", "npm_config_color", "npm_config_progress",
            "HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy", "NO_PROXY", "no_proxy");

    /** {@code GIT_CONFIG_KEY_<n>} and {@code GIT_CONFIG_VALUE_<n>}: how git gets the proxy without argv (spec 017). */
    static final java.util.regex.Pattern GIT_CONFIG_ENTRY = java.util.regex.Pattern.compile("GIT_CONFIG_(KEY|VALUE)_[0-9]{1,2}");

    static void checkArgs(List<String> args) {
        if (args.size() > MAX_ARGS) {
            throw new IllegalArgumentException("too many arguments: " + args.size() + " (at most " + MAX_ARGS + ")");
        }
        long total = 0;
        for (String a : args) {
            if (a == null || a.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("argument contains a NUL character");
            }
            int bytes = a.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_ARG_BYTES) {
                throw new IllegalArgumentException("argument too long: " + bytes + " bytes (at most " + MAX_ARG_BYTES + ")");
            }
            total += bytes;
        }
        if (total > MAX_TOTAL_BYTES) {
            throw new IllegalArgumentException("arguments too long: " + total + " bytes (at most " + MAX_TOTAL_BYTES + ")");
        }
    }

    static void checkEnv(Map<String, String> env) {
        for (Map.Entry<String, String> v : env.entrySet()) {
            if (!ENV_NAMES.contains(v.getKey()) && !GIT_CONFIG_ENTRY.matcher(v.getKey()).matches() && !ToolDefinition.envNameAllowed(v.getKey())) {
                throw new IllegalArgumentException("environment variable " + v.getKey() + " is not one JHarness passes to processes");
            }
            if (v.getValue() == null || v.getValue().indexOf('\0') >= 0) {
                throw new IllegalArgumentException("environment variable " + v.getKey() + " has no value or a NUL character");
            }
        }
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
        private final List<String> secrets;
        private long total;

        Capture(int tailChars, List<String> secrets) {
            this.tailChars = tailChars;
            // Longest first, so a secret that contains another is replaced whole.
            this.secrets = secrets.stream().filter(x -> x != null && !x.isEmpty()).distinct()
                    .sorted(java.util.Comparator.comparingInt(String::length).reversed()).toList();
        }

        /** Secrets reach children through the environment, and JVMs echo them ("Picked up ..."). */
        private String redact(String s) {
            for (String x : secrets) {
                s = s.replace(x, "***");
            }
            return s;
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
            return new ProcessResult(code, redact(t), cut, redact(full.toString()), total > FULL_OUTPUT_CHARS, timedOut, ms);
        }
    }
}
