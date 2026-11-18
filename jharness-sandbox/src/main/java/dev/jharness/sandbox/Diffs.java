package dev.jharness.sandbox;

import java.util.Arrays;
import java.util.List;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;

/** Unified diffs for review (US-2.3), cut at {@link #MAX_LINES}. */
public final class Diffs {

    public static final int MAX_LINES = 200;

    private Diffs() {}

    /** @param before null for a new file; @param after null for a removed file */
    public static String unified(String path, String before, String after) {
        List<String> a = before == null ? List.of() : Arrays.asList(before.split("\n", -1));
        List<String> b = after == null ? List.of() : Arrays.asList(after.split("\n", -1));
        Patch<String> patch = DiffUtils.diff(a, b);
        if (patch.getDeltas().isEmpty()) {
            return "";
        }
        List<String> lines = UnifiedDiffUtils.generateUnifiedDiff(before == null ? "/dev/null" : "a/" + path,
                after == null ? "/dev/null" : "b/" + path, a, patch, 3);
        if (lines.size() > MAX_LINES) {
            int more = lines.size() - MAX_LINES;
            lines = new java.util.ArrayList<>(lines.subList(0, MAX_LINES));
            lines.add("[" + more + " more diff lines]");
        }
        return String.join("\n", lines) + "\n";
    }
}
