package dev.jharness.process;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves the tools JHarness may run to absolute paths (D-008, D-027). {@code PATH} entries that
 * are relative or inside the workspace are skipped, so a repository can't plant its own {@code git};
 * so are files and folders anyone may write to.
 */
public final class Executables {

    /** The tools JHarness runs itself (D-008, D-027); the rest come from tool definitions (D-066). */
    public static final Set<String> BUILT_IN = Set.of("git", "rg");

    private Executables() {}

    public static Optional<Executable> resolve(String name, String pathEnv, Path workspace) {
        if (!ToolDefinition.EXECUTABLE.matcher(name).matches() || ToolDefinition.REFUSED.contains(name)) {
            throw new IllegalArgumentException(name + " is not a program JHarness runs (shells and command runners never are; constitution Article IV)");
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
                if (ws != null && real.startsWith(ws) || othersCanWrite(real) || othersCanWrite(real.getParent())) {
                    continue;
                }
                return Optional.of(new Executable(name, real, d));
            }
        }
        return Optional.empty();
    }

    /** Resolves from this process's {@code PATH}. */
    public static Optional<Executable> resolve(String name, Path workspace) {
        return resolve(name, System.getenv("PATH"), workspace);
    }

    /** A file or folder anyone may write to (mode {@code o+w}) could be swapped under us (spec 015, AC-05). */
    static boolean othersCanWrite(Path p) {
        if (p == null) {
            return false;
        }
        try {
            return Files.getPosixFilePermissions(p).contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE);
        } catch (IOException | UnsupportedOperationException e) {
            return true;
        }
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
