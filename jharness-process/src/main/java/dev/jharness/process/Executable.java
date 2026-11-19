package dev.jharness.process;

import java.nio.file.Path;

/**
 * A tool resolved to an absolute path at startup. Production code can only get one from
 * {@link Executables#resolve}, which knows git, {@code rg}, Maven, Gradle and npm (constitution Article IV),
 * or as a wrapper inside a trusted project ({@link BuildOperations}).
 */
public final class Executable {

    private final String name;
    private final Path path;
    private final Path searchDir;

    Executable(String name, Path path) {
        this(name, path, path.getParent());
    }

    Executable(String name, Path path, Path searchDir) {
        this.name = name;
        this.path = path;
        this.searchDir = searchDir;
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

    @Override
    public String toString() {
        return name + "=" + path;
    }
}
