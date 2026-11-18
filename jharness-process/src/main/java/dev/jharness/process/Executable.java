package dev.jharness.process;

import java.nio.file.Path;

/**
 * A tool resolved to an absolute path at startup. Production code can only get one from
 * {@link Executables#resolve}, which knows git and {@code rg} only (constitution Article IV).
 */
public final class Executable {

    private final String name;
    private final Path path;

    Executable(String name, Path path) {
        this.name = name;
        this.path = path;
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
