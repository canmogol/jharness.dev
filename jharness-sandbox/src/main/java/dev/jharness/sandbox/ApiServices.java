package dev.jharness.sandbox;

import java.util.function.Supplier;

import dev.jharness.events.EventBus;
import dev.jharness.events.Origin;
import dev.jharness.net.http.HttpOperations;
import dev.jharness.process.BuildOperations;
import dev.jharness.process.GitOperations;
import dev.jharness.process.Search;
import dev.jharness.security.FilePolicy;
import dev.jharness.security.PermissionGate;
import dev.jharness.security.Workspace;

/**
 * What the {@code api} facade needs from the host; {@code files} is the file policy (D-041). {@code git} is null when git is not installed;
 * {@code checkpoints} is null when changes are not checkpointed; {@code builds} is null when the session can't run build tools;
 * {@code http} is null when the session has no HTTP access (spec 016); {@code subagents} is null where
 * {@code api.spawnAgent} isn't available, such as inside a subagent (spec 026); {@code hooks} is null
 * where no hooks run, such as inside a hook (spec 034).
 */
public record ApiServices(FilePolicy files, PermissionGate gate, EventBus bus, Supplier<Origin> origin, Search search,
        GitOperations git, Checkpoints checkpoints, BuildOperations builds, HttpOperations http, Subagents subagents, Hooks hooks) {

    /** Without hooks. */
    public ApiServices(FilePolicy files, PermissionGate gate, EventBus bus, Supplier<Origin> origin, Search search, GitOperations git,
            Checkpoints checkpoints, BuildOperations builds, HttpOperations http, Subagents subagents) {
        this(files, gate, bus, origin, search, git, checkpoints, builds, http, subagents, null);
    }

    /** The same services with these hooks (null for none). */
    public ApiServices withHooks(Hooks h) {
        return new ApiServices(files, gate, bus, origin, search, git, checkpoints, builds, http, subagents, h);
    }

    /** Without subagents. */
    public ApiServices(FilePolicy files, PermissionGate gate, EventBus bus, Supplier<Origin> origin, Search search, GitOperations git,
            Checkpoints checkpoints, BuildOperations builds, HttpOperations http) {
        this(files, gate, bus, origin, search, git, checkpoints, builds, http, null);
    }

    /** Without checkpoints and build tools (tests and the smoke binary). */
    public ApiServices(FilePolicy files, PermissionGate gate, EventBus bus, Supplier<Origin> origin, Search search, GitOperations git) {
        this(files, gate, bus, origin, search, git, null, null, null);
    }

    public ApiServices(FilePolicy files, PermissionGate gate, EventBus bus, Supplier<Origin> origin, Search search, GitOperations git,
            Checkpoints checkpoints) {
        this(files, gate, bus, origin, search, git, checkpoints, null, null);
    }

    /** Without HTTP access. */
    public ApiServices(FilePolicy files, PermissionGate gate, EventBus bus, Supplier<Origin> origin, Search search, GitOperations git,
            Checkpoints checkpoints, BuildOperations builds) {
        this(files, gate, bus, origin, search, git, checkpoints, builds, null);
    }

    public Workspace workspace() {
        return files.workspace();
    }
}
