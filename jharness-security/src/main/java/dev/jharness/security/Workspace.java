package dev.jharness.security;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Confines {@code api} paths to the workspace (AC-07). A path is accepted only if, after
 * normalising {@code ..} and following every symlink in the part that exists, it is inside the
 * workspace's real path. Paths that don't exist yet are checked through their nearest existing
 * parent, so a write can't go through a symlinked folder that leads outside.
 *
 * <p>Read-only mounted folders and the deny list come with the file policy feature.
 */
public final class Workspace {

    private final Path root;

    public Workspace(Path root) {
        try {
            this.root = root.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException("workspace " + root + " does not exist", e);
        }
        if (!Files.isDirectory(this.root)) {
            throw new IllegalArgumentException("workspace " + root + " is not a folder");
        }
    }

    public Path root() {
        return root;
    }

    /**
     * Resolves a guest path ({@code src/App.java}, {@code ./x}, or an absolute path inside the
     * workspace) to a real path inside the workspace.
     *
     * @throws PathException if the path is empty, malformed or leads outside
     */
    public Path resolve(String path) {
        if (path == null || path.isBlank()) {
            throw new PathException("path is empty");
        }
        if (path.indexOf('\0') >= 0) {
            throw new PathException("path contains a NUL character");
        }
        Path p;
        try {
            p = root.resolve(path).normalize();
        } catch (InvalidPathException e) {
            throw new PathException("invalid path " + path);
        }
        if (!p.startsWith(root)) {
            throw new PathException(path + " is outside the workspace");
        }
        Path existing = p;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new PathException(path + " is outside the workspace");
        }
        Path real;
        try {
            real = existing.toRealPath();
        } catch (IOException e) {
            // A dangling symlink: refuse rather than guess where it points.
            throw new PathException(path + " goes through a broken symlink");
        }
        if (!real.startsWith(root)) {
            throw new PathException(path + " leads outside the workspace through a symlink");
        }
        return real.resolve(existing.relativize(p));
    }

    /** The path relative to the workspace, with '/' separators, for results and events. */
    public String relative(Path p) {
        String r = root.relativize(p).toString().replace('\\', '/');
        return r.isEmpty() ? "." : r;
    }

    /** A path was rejected by {@link #resolve}. */
    public static final class PathException extends RuntimeException {
        public PathException(String message) {
            super(message);
        }
    }
}
