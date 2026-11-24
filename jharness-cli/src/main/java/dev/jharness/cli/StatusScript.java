package dev.jharness.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;

import dev.jharness.config.SandboxSettings;
import dev.jharness.events.EventBus;
import dev.jharness.events.Json;
import dev.jharness.events.Origin;
import dev.jharness.sandbox.AgentIsolate;
import dev.jharness.sandbox.IsolateManager;
import dev.jharness.sandbox.RunResult;

/**
 * {@code ~/.jharness/statusline.js} (spec 042, D-080): {@code status(snapshot)} returns the whole status line.
 * It runs in its own isolate with the subagent sandbox limits and no {@code api}. Its isolate's own events go to a
 * private bus, so a refresh never adds to the session log or triggers another refresh.
 */
final class StatusScript implements AutoCloseable {

    static final long MAX_FILE_BYTES = 64 * 1024;
    /** Longest one refresh may take before the script is turned off for the session. */
    static final Duration TIMEOUT = Duration.ofSeconds(1);
    /** The first run also warms the isolate up, so it may take longer. */
    static final Duration FIRST_TIMEOUT = Duration.ofSeconds(10);
    /** Most characters of a returned line kept; the terminal width cuts it further. */
    static final int MAX_CHARS = 1000;

    /** What one run gave: the line, or why there is none ({@code error}, {@code limit}, {@code timeout}, {@code type}). */
    record Outcome(String line, String reason, String error) {
        boolean ok() {
            return line != null;
        }
    }

    private final String source;
    private final AgentIsolate isolate;
    private final ExecutorService runner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "jharness-statusline");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean off;
    private volatile boolean warm;

    private StatusScript(String source, AgentIsolate isolate) {
        this.source = source;
        this.isolate = isolate;
    }

    static Path file(Path home) {
        return home.resolve(".jharness").resolve("statusline.js");
    }

    /**
     * The script, or null when there is no file.
     *
     * @throws IOException when the file can't be used; the message says why
     */
    static StatusScript load(Path home, SandboxSettings sandbox) throws IOException {
        Path f = file(home);
        if (!Files.exists(f, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("must be a regular file, not a link");
        }
        if (Files.size(f) > MAX_FILE_BYTES) {
            throw new IOException("is larger than 64 KB");
        }
        try {
            if (Files.getPosixFilePermissions(f, LinkOption.NOFOLLOW_LINKS).contains(PosixFilePermission.OTHERS_WRITE)) {
                throw new IOException("others can write it; run chmod o-w on it");
            }
        } catch (UnsupportedOperationException ignored) {
            // Not a POSIX file system.
        }
        String text = Files.readString(f, StandardCharsets.UTF_8);
        EventBus quiet = new EventBus("statusline");
        AgentIsolate iso = new IsolateManager(sandbox, quiet, () -> Origin.main(0)).create("statusline", "subagent");
        return new StatusScript(text, iso);
    }

    /** Whether a timeout turned the script off for the rest of the session. */
    boolean off() {
        return off;
    }

    /** Runs {@code status(snapshot)} once. Never throws. */
    Outcome run(JsonNode snapshot) {
        if (off) {
            return new Outcome(null, "timeout", "turned off after a timeout");
        }
        String js = "delete globalThis.api;\n" + source + "\n;(function () {\n"
                + "  if (typeof status !== 'function') { throw new Error('statusline.js defines no status(s) function'); }\n"
                + "  const r = status(" + Json.write(snapshot) + ");\n"
                + "  return JSON.stringify(typeof r === 'string' ? {line: r} : {type: r === null ? 'null' : typeof r});\n})()";
        Future<RunResult> f = runner.submit(() -> isolate.run(js, null));
        RunResult r;
        long limit = (warm ? TIMEOUT : FIRST_TIMEOUT).toMillis();
        try {
            r = f.get(limit, TimeUnit.MILLISECONDS);
            warm = true;
        } catch (TimeoutException e) {
            off = true;
            isolate.cancel();
            f.cancel(true);
            return new Outcome(null, "timeout", "took longer than " + limit + " ms; the default line is used for this session");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(null, "error", "interrupted");
        } catch (Exception e) {
            return new Outcome(null, "error", String.valueOf(e.getMessage()));
        }
        if (!r.ok()) {
            String reason = r.limit() != null || r.status().equals("limit") ? "limit" : "error";
            return new Outcome(null, reason, r.error() != null ? r.error() : r.status());
        }
        JsonNode v;
        try {
            v = Json.parse(r.value());
        } catch (RuntimeException e) {
            return new Outcome(null, "type", "status(s) gave something that isn't a string");
        }
        if (!v.has("line")) {
            return new Outcome(null, "type", "status(s) returned " + v.path("type").asText("nothing") + ", not a string");
        }
        String line = v.get("line").asText();
        int nl = line.indexOf('\n');
        if (nl >= 0) {
            line = line.substring(0, nl);
        }
        if (line.length() > MAX_CHARS) {
            line = line.substring(0, MAX_CHARS);
        }
        return new Outcome(TerminalSafe.escape(line), null, null);
    }

    @Override
    public void close() {
        runner.shutdownNow();
        isolate.close();
    }
}
