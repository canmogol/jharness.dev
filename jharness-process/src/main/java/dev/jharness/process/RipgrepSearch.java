package dev.jharness.process;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.jharness.events.Json;
import dev.jharness.security.Workspace;

/**
 * Search through {@code rg} (D-027): {@code --json} output parsed by the host, the pattern after
 * {@code -e}, globs after {@code -g}, paths after {@code --}, and no config file. The blocked flags
 * ({@code --pre}, {@code --pre-glob}, {@code -z}, {@code -L}) are never built.
 */
public final class RipgrepSearch implements Search {

    static final Set<String> BLOCKED = Set.of("--pre", "--pre-glob", "-z", "--search-zip", "-L", "--follow");

    private final ProcessRunner runner;
    private final Executable rg;
    private final Workspace ws;
    private final Duration timeout;

    public RipgrepSearch(ProcessRunner runner, Executable rg, Workspace ws, Duration timeout) {
        if (!rg.name().equals("rg")) {
            throw new IllegalArgumentException("not rg: " + rg);
        }
        this.runner = runner;
        this.rg = rg;
        this.ws = ws;
        this.timeout = timeout;
    }

    @Override
    public String engine() {
        return "rg";
    }

    List<String> grepArgs(String pattern, Path dir, String glob, boolean ignoreCase) {
        List<String> a = new ArrayList<>(List.of("--json", "--no-config", "--sort=path", "--no-messages"));
        if (ignoreCase) {
            a.add("-i");
        }
        if (glob != null) {
            a.add("-g");
            a.add(glob);
        }
        a.add("-e");
        a.add(pattern);
        a.add("--");
        a.add(ws.relative(dir));
        check(a);
        return a;
    }

    List<String> listArgs(Path dir, String glob) {
        List<String> a = new ArrayList<>(List.of("--files", "--no-config", "--sort=path", "--no-messages"));
        if (glob != null) {
            a.add("-g");
            a.add(glob);
        }
        a.add("--");
        a.add(ws.relative(dir));
        check(a);
        return a;
    }

    /** Belt and braces: the options this class builds never include a blocked flag. */
    private static void check(List<String> a) {
        for (int i = 0; i < a.size(); i++) {
            String s = a.get(i);
            if (s.equals("--")) {
                return;
            }
            boolean value = i > 0 && (a.get(i - 1).equals("-e") || a.get(i - 1).equals("-g"));
            if (!value && BLOCKED.stream().anyMatch(b -> s.equals(b) || s.startsWith(b + "="))) {
                throw new IllegalStateException("blocked rg flag " + s);
            }
        }
    }

    @Override
    public ObjectNode grep(String pattern, Path dir, String glob, boolean ignoreCase, int max) {
        ProcessResult r = runner.run(rg, grepArgs(pattern, dir, glob, ignoreCase), ws.root(), timeout, Map.of());
        if (r.timedOut()) {
            throw new OperationException("rg timed out after " + timeout.toSeconds() + " s");
        }
        if (r.exitCode() == 2 && !r.full().contains("\"type\":\"match\"")) {
            throw new OperationException("rg failed: " + Search.clip(r.output()));
        }
        ObjectNode out = Json.object();
        ArrayNode matches = out.putArray("matches");
        boolean truncated = r.fullTruncated();
        for (String line : r.full().split("\n")) {
            if (!line.startsWith("{")) {
                continue;
            }
            JsonNode j;
            try {
                j = Json.parse(line);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (!"match".equals(j.path("type").asText())) {
                continue;
            }
            if (matches.size() >= max) {
                truncated = true;
                break;
            }
            JsonNode d = j.path("data");
            matches.addObject()
                    .put("path", d.path("path").path("text").asText().replaceFirst("^\\./", ""))
                    .put("line", d.path("line_number").asInt())
                    .put("text", Search.clip(d.path("lines").path("text").asText()));
        }
        out.put("truncated", truncated);
        return out;
    }

    @Override
    public ObjectNode listFiles(Path dir, String glob, int max) {
        ProcessResult r = runner.run(rg, listArgs(dir, glob), ws.root(), timeout, Map.of());
        if (r.timedOut()) {
            throw new OperationException("rg timed out after " + timeout.toSeconds() + " s");
        }
        if (r.exitCode() == 2) {
            throw new OperationException("rg failed: " + Search.clip(r.output()));
        }
        ObjectNode out = Json.object();
        ArrayNode files = out.putArray("files");
        boolean truncated = r.fullTruncated();
        for (String line : r.full().split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            if (files.size() >= max) {
                truncated = true;
                break;
            }
            files.add(line.replaceFirst("^\\./", ""));
        }
        out.put("truncated", truncated);
        return out;
    }
}
