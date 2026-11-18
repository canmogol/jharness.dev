package dev.jharness.sandbox;

import java.util.function.Supplier;

import dev.jharness.events.EventBus;
import dev.jharness.events.Origin;
import dev.jharness.process.GitOperations;
import dev.jharness.process.Search;
import dev.jharness.security.FilePolicy;
import dev.jharness.security.PermissionGate;
import dev.jharness.security.Workspace;

/**
 * What the {@code api} facade needs from the host; {@code files} is the file policy (D-041). {@code git} is null when git is not installed;
 * {@code checkpoints} is null when changes are not checkpointed.
 */
public record ApiServices(FilePolicy files, PermissionGate gate, EventBus bus, Supplier<Origin> origin, Search search,
        GitOperations git, Checkpoints checkpoints) {

    /** Without checkpoints (tests and the smoke binary). */
    public ApiServices(FilePolicy files, PermissionGate gate, EventBus bus, Supplier<Origin> origin, Search search, GitOperations git) {
        this(files, gate, bus, origin, search, git, null);
    }

    public Workspace workspace() {
        return files.workspace();
    }
}
