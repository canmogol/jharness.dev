package dev.jharness.process;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves the tools JHarness may run to absolute paths (D-008, D-027). {@code PATH} entries that
 * are relative or inside the workspace are skipped, so a repository can't plant its own {@code git}.
 */
public final class Executables {

    /** Tools allowed in this feature; Maven, Gradle and npm arrive in P2. */
    public static final Set<String> ALLOWED = Set.of("git", "rg");

    private Executables() {}

    public static Optional<Executable> resolve(String name, String pathEnv, Path workspace) {
        if (!ALLOWED.contains(name)) {
            throw new IllegalArgumentException(name + " is not a tool JHarness runs (allowed: git, rg)");
        }
        if (pathEnv == null) {
            return Optional.empty();
        }
        Path ws = realOrSelf(workspace);
        for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
            if (dir.isEmpty()) {
                continue;
            }
            Path d = Path.of(dir);
            if (!d.isAbsolute()) {
                continue;
            }
            Path realDir = realOrSelf(d);
            if (ws != null && realDir.startsWith(ws)) {
                continue;
            }
            Path candidate = d.resolve(name);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                Path real = realOrSelf(candidate);
                if (ws != null && real.startsWith(ws)) {
                    continue;
                }
                return Optional.of(new Executable(name, real));
            }
        }
        return Optional.empty();
    }

    /** Resolves from this process's {@code PATH}. */
    public static Optional<Executable> resolve(String name, Path workspace) {
        return resolve(name, System.getenv("PATH"), workspace);
    }

    private static Path realOrSelf(Path p) {
        if (p == null) {
            return null;
        }
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }
}
