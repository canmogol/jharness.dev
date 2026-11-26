package dev.jharness.security;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The file policy (D-041): the workspace is read-write, mounted folders are read-only, deny-list
 * files can't be read or written, and {@code .jharness/**} can't be written. It applies in every
 * permission mode.
 */
public final class FilePolicy {

    /** @param display workspace-relative for workspace files, absolute for mounted ones */
    public record Target(Path path, String display, String relative, boolean mounted) {}

    private final Workspace workspace;
    private final List<Workspace> mounts;
    private final DenyList deny;

    public FilePolicy(Workspace workspace, List<Path> mounts, List<String> extraDeny) {
        this.workspace = workspace;
        List<Workspace> m = new ArrayList<>();
        for (Path p : mounts) {
            m.add(new Workspace(p));
        }
        this.mounts = List.copyOf(m);
        this.deny = new DenyList(extraDeny);
    }

    public static FilePolicy workspaceOnly(Workspace ws) {
        return new FilePolicy(ws, List.of(), List.of());
    }

    public Workspace workspace() {
        return workspace;
    }

    public List<Path> mounts() {
        return mounts.stream().map(Workspace::root).toList();
    }

    public DenyList denyList() {
        return deny;
    }

    private Optional<Workspace> mountFor(String path) {
        if (path == null) {
            return Optional.empty();
        }
        Path p;
        try {
            p = Path.of(path);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (!p.isAbsolute()) {
            return Optional.empty();
        }
        Path n = p.normalize();
        if (n.startsWith(workspace.root())) {
            return Optional.empty();
        }
        return mounts.stream().filter(m -> n.startsWith(m.root())).findFirst();
    }

    /** A path the agent may read: in the workspace or a mount, and not denied. */
    public Target read(String path) {
        Optional<Workspace> mount = mountFor(path);
        if (mount.isPresent()) {
            Path real = mount.get().resolve(path);
            String rel = mount.get().relative(real);
            check(rel, path);
            return new Target(real, real.toString(), rel, true);
        }
        Path real = workspace.resolve(path);
        String rel = workspace.relative(real);
        check(rel, path);
        return new Target(real, rel, rel, false);
    }

    /** A path the agent may write: in the workspace, not denied, not under {@code .jharness/}. */
    public Target write(String path) {
        if (mountFor(path).isPresent()) {
            throw new Workspace.PathException(path + " is in a read-only mounted folder");
        }
        Path real = workspace.resolve(path);
        String rel = workspace.relative(real);
        check(rel, path);
        if (rel.equals(".jharness") || rel.startsWith(".jharness/")) {
            throw new Workspace.PathException(path + " is JHarness project configuration, which the agent can't change");
        }
        return new Target(real, rel, rel, false);
    }

    /**
     * A path the agent may delete (spec 056): as {@link #write}, and never the workspace root or {@code .git}, nor
     * anything under it.
     */
    public Target delete(String path) {
        Target t = write(path);
        String rel = t.relative();
        if (rel.isEmpty() || rel.equals(".")) {
            throw new Workspace.PathException(path + " is the workspace itself, which the agent can't delete");
        }
        if (rel.equals(".git") || rel.startsWith(".git/") || rel.endsWith("/.git") || rel.contains("/.git/")) {
            throw new Workspace.PathException(path + " is git's own data, which the agent can't delete");
        }
        return t;
    }

    /** Whether a workspace-relative path is on the deny list (spec 056: a folder to delete must hold none). */
    public boolean denied(String relative) {
        return deny.denied(relative);
    }

    /** Whether a result path (workspace-relative, or absolute in a mount) is hidden by the deny list. */
    public boolean hidden(String resultPath) {
        Optional<Workspace> mount = mountFor(resultPath);
        if (mount.isPresent()) {
            return deny.denied(mount.get().root().relativize(Path.of(resultPath).normalize()).toString().replace('\\', '/'));
        }
        return deny.denied(resultPath);
    }

    private void check(String rel, String asGiven) {
        if (deny.denied(rel)) {
            throw new Workspace.PathException(asGiven + " is on the deny list (secrets, keys and .git internals are off limits)");
        }
    }
}
