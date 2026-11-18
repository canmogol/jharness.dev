package dev.jharness.security;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

/**
 * Globs for files the agent may never read or write (D-041). A glob without '/' matches the file or
 * folder name at any depth; a glob with '/' matches the path relative to the workspace or mount. A
 * folder glob {@code x/**} also matches the folder {@code x} itself.
 */
public final class DenyList {

    public static final List<String> BUILT_IN = List.of(".env", ".env.*", "*.pem", "*.key", "id_rsa*", "id_ecdsa*",
            "id_ed25519*", ".git/**");

    private final List<String> globs;
    private final List<PathMatcher> byName = new ArrayList<>();
    private final List<PathMatcher> byPath = new ArrayList<>();

    public DenyList(List<String> extra) {
        List<String> all = new ArrayList<>(BUILT_IN);
        all.addAll(extra);
        this.globs = List.copyOf(all);
        for (String g : globs) {
            PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + g);
            if (g.contains("/")) {
                byPath.add(m);
                if (g.endsWith("/**")) {
                    byPath.add(FileSystems.getDefault().getPathMatcher("glob:" + g.substring(0, g.length() - 3)));
                    // A folder glob also applies below nested folders, e.g. sub/.git/config.
                    byPath.add(FileSystems.getDefault().getPathMatcher("glob:**/" + g));
                    byPath.add(FileSystems.getDefault().getPathMatcher("glob:**/" + g.substring(0, g.length() - 3)));
                }
            } else {
                byName.add(m);
            }
        }
    }

    public List<String> globs() {
        return globs;
    }

    /** @param relative path relative to the workspace or mount root, '/'-separated */
    public boolean denied(String relative) {
        if (relative == null || relative.isEmpty() || relative.equals(".")) {
            return false;
        }
        Path p = Path.of(relative);
        for (PathMatcher m : byPath) {
            if (m.matches(p)) {
                return true;
            }
        }
        for (Path part : p) {
            for (PathMatcher m : byName) {
                if (m.matches(part)) {
                    return true;
                }
            }
        }
        return false;
    }
}
