package dev.jharness.process;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.jharness.config.Timeouts;
import dev.jharness.events.EventBus;
import dev.jharness.events.Origin;
import dev.jharness.security.DenyList;
import dev.jharness.security.Workspace;

/**
 * Backs {@code api.grep} and {@code api.listFiles}. Both implementations return the same shapes:
 * {@code {"matches":[{"path","line","text"}],"truncated"}} and {@code {"files":[…],"truncated"}}, with
 * workspace-relative paths sorted by path (D-027).
 */
public interface Search {

    int DEFAULT_MAX = 200;
    int MAX = 5000;
    int LINE_CHARS = 500;

    /** @param dir a real path inside the workspace; @param glob optional; @param max results */
    ObjectNode grep(String pattern, Path dir, String glob, boolean ignoreCase, int max);

    ObjectNode listFiles(Path dir, String glob, int max);

    /** Which implementation, for events and tests: {@code rg} or {@code java}. */
    String engine();

    /** {@code rg} when it is installed and the OS sandbox works, else the Java fallback (D-048). */
    static Search create(Workspace ws, ProcessRunner runner, Timeouts timeouts) {
        return create(ws, runner, timeouts, new DenyList(java.util.List.of()));
    }

    /** @param deny files left out of results ({@code rg -g '!glob'}, and the same globs in the Java walk) */
    static Search create(Workspace ws, ProcessRunner runner, Timeouts timeouts, DenyList deny) {
        Optional<Executable> rg = Executables.resolve("rg", ws.root());
        if (rg.isPresent() && runner.sandbox().available()) {
            return new RipgrepSearch(runner, rg.get(), ws, timeouts.rg(), deny);
        }
        return new JavaSearch(ws, deny);
    }

    /** Paths in results: workspace-relative inside the workspace, absolute elsewhere (mounted folders). */
    static String display(Workspace ws, Path p) {
        return p.startsWith(ws.root()) ? ws.relative(p) : p.toString();
    }

    static String clip(String line) {
        String s = line.endsWith("\n") ? line.substring(0, line.length() - 1) : line;
        if (s.endsWith("\r")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.length() > LINE_CHARS ? s.substring(0, LINE_CHARS) + "…" : s;
    }
}
