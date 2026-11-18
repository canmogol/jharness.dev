package dev.jharness.sandbox;

import java.util.function.Supplier;

import dev.jharness.events.EventBus;
import dev.jharness.events.Origin;
import dev.jharness.process.GitOperations;
import dev.jharness.process.Search;
import dev.jharness.security.PermissionGate;
import dev.jharness.security.Workspace;

/**
 * What the {@code api} facade needs from the host. {@code git} is null when git is not installed.
 */
public record ApiServices(Workspace workspace, PermissionGate gate, EventBus bus, Supplier<Origin> origin, Search search,
        GitOperations git) {
}
