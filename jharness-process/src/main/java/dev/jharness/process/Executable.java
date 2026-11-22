package dev.jharness.process;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Objects;

/**
 * A tool resolved to an absolute path at startup. Production code can only get one from
 * {@link Executables#resolve}, which knows git, {@code rg}, Maven, Gradle and npm (constitution Article IV),
 * or as a wrapper inside a trusted project ({@link BuildOperations}).
 *
 * <p>Each one is pinned to the file it resolved to (spec 015): {@link ProcessRunner} calls {@link #verify}
 * before every start, so a binary replaced during the session never runs.
 */
public final class Executable {

    /** File identity when resolved; all null when the file didn't exist. */
    record Pin(Object fileKey, long size, FileTime modified) {
        static Pin of(Path p) {
            try {
                BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
                return new Pin(a.fileKey(), a.size(), a.lastModifiedTime());
            } catch (IOException e) {
                return new Pin(null, -1, null);
            }
        }
    }

    private final String name;
    private final Path path;
    private final Path searchDir;
    private final Pin pin;

    Executable(String name, Path path) {
        this(name, path, path.getParent());
    }

    Executable(String name, Path path, Path searchDir) {
        this.name = name;
        this.path = path;
        this.searchDir = searchDir;
        this.pin = Pin.of(path);
    }

    /** The {@code PATH} folder it was found in (before symlinks); npm needs {@code node} from there. */
    public Path searchDir() {
        return searchDir;
    }

    public String name() {
        return name;
    }

    public Path path() {
        return path;
    }

    /** Throws if the file is no longer the one that was resolved (replaced, rewritten or removed). */
    void verify() {
        if (!Objects.equals(Pin.of(path), pin)) {
            throw new OperationException(name + " changed since it was resolved (" + path + "); restart JHarness");
        }
    }

    @Override
    public String toString() {
        return name + "=" + path;
    }
}
