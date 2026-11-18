package dev.jharness.sandbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.jharness.events.EventBus;
import dev.jharness.events.Json;
import dev.jharness.events.Origin;
import dev.jharness.events.SessionLog;

/**
 * Per-turn file checkpoints (D-044): the content of each file before the agent first changes it in
 * a turn, and the hash of what the agent left, under
 * {@code ~/.jharness/checkpoints/<project-hash>/<session-id>/<turn>/}.
 */
public final class Checkpoints {

    public static final long MAX_BYTES = 10L << 20;

    /** One file in one turn's checkpoint. */
    record Entry(String display, Path path, boolean existed, String blob, String after, boolean tooLarge) {}

    /** The outcome of {@link #restore}. */
    public record Restore(boolean done, List<String> restored, List<String> conflicts, List<String> notRestorable,
            List<String> gitOps, List<Integer> turns) {

        public String message() {
            if (turns.isEmpty()) {
                return "Nothing to undo.";
            }
            StringBuilder sb = new StringBuilder();
            if (!done) {
                sb.append("Not undone: these files changed since the agent wrote them: ").append(String.join(", ", conflicts))
                        .append(". Use --force to restore them anyway.");
                return sb.toString();
            }
            sb.append(restored.isEmpty() ? "No files to restore." : "Restored " + String.join(", ", restored) + ".");
            if (!notRestorable.isEmpty()) {
                sb.append(" Not restorable (over 10 MB): ").append(String.join(", ", notRestorable)).append('.');
            }
            if (!gitOps.isEmpty()) {
                sb.append(" Git state was not undone (the undone turns ran git ").append(String.join(", ", gitOps)).append(").");
            }
            return sb.toString();
        }
    }

    private final Path root;
    private final EventBus bus;
    private final Supplier<Origin> origin;
    /** turn -> path -> entry, in the order files were first changed. */
    private final TreeMap<Integer, Map<Path, Entry>> turns = new TreeMap<>();
    private final TreeMap<Integer, Set<String>> git = new TreeMap<>();
    private int blobs;

    public Checkpoints(Path root, EventBus bus, Supplier<Origin> origin) {
        this.root = root;
        this.bus = bus;
        this.origin = origin;
    }

    public static Path rootFor(Path home, Path workspace, String session) {
        return home.resolve(".jharness").resolve("checkpoints").resolve(SessionLog.projectHash(workspace)).resolve(session);
    }

    private static String sha(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hashOf(Path p) throws IOException {
        return Files.exists(p, LinkOption.NOFOLLOW_LINKS) ? sha(Files.readAllBytes(p)) : "absent";
    }

    private Path privateDir(Path dir) throws IOException {
        Files.createDirectories(dir);
        for (Path d = dir; d != null && d.startsWith(root.getParent().getParent()); d = d.getParent()) {
            Files.setPosixFilePermissions(d, PosixFilePermissions.fromString("rwx------"));
        }
        return dir;
    }

    /** Saves the file's current content the first time it is changed in this turn. */
    public synchronized void beforeChange(int turn, Path file, String display) {
        Map<Path, Entry> t = turns.computeIfAbsent(turn, k -> new LinkedHashMap<>());
        if (t.containsKey(file)) {
            return;
        }
        try {
            boolean existed = Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS);
            String blob = null;
            boolean large = false;
            if (existed) {
                if (Files.size(file) > MAX_BYTES) {
                    large = true;
                } else {
                    Path dir = privateDir(root.resolve(Integer.toString(turn)).resolve("blobs"));
                    blob = Integer.toString(++blobs);
                    Path b = dir.resolve(blob);
                    Files.write(b, Files.readAllBytes(file));
                    Files.setPosixFilePermissions(b, PosixFilePermissions.fromString("rw-------"));
                }
            }
            t.put(file, new Entry(display, file, existed, blob, null, large));
            writeManifest(turn);
            bus.publish(origin.get(), "checkpoint.saved", Json.object().put("path", display).put("checkpointTurn", turn)
                    .put("existed", existed).put("tooLarge", large));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot save checkpoint for " + display, e);
        }
    }

    /** Records what the agent left, for the conflict check at undo time. */
    public synchronized void afterChange(int turn, Path file) {
        Map<Path, Entry> t = turns.get(turn);
        if (t == null || !t.containsKey(file)) {
            return;
        }
        try {
            Entry e = t.get(file);
            t.put(file, new Entry(e.display(), e.path(), e.existed(), e.blob(), hashOf(file), e.tooLarge()));
            writeManifest(turn);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void touchedGit(int turn, String op) {
        git.computeIfAbsent(turn, k -> new LinkedHashSet<>()).add(op);
    }

    /** Turns that changed files or ran git, oldest first. */
    public synchronized List<Integer> turns() {
        Set<Integer> s = new java.util.TreeSet<>(turns.keySet());
        s.addAll(git.keySet());
        return List.copyOf(s);
    }

    private void writeManifest(int turn) throws IOException {
        ObjectNode m = Json.object();
        ArrayNode files = m.putArray("files");
        for (Entry e : turns.get(turn).values()) {
            ObjectNode f = files.addObject().put("path", e.display()).put("existed", e.existed()).put("tooLarge", e.tooLarge());
            if (e.blob() != null) {
                f.put("blob", e.blob());
            }
            if (e.after() != null) {
                f.put("after", e.after());
            }
        }
        Path dir = privateDir(root.resolve(Integer.toString(turn)));
        Path mf = dir.resolve("manifest.json");
        Files.writeString(mf, Json.write(m), StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(mf, PosixFilePermissions.fromString("rw-------"));
    }

    /**
     * Restores every file changed in turns after {@code keepThrough} to its content before the first of
     * those turns. Stops without changing anything if a file changed since the agent last wrote it,
     * unless {@code force}.
     */
    public synchronized Restore restore(int keepThrough, boolean force) {
        List<Integer> undone = turns().stream().filter(t -> t > keepThrough).toList();
        // earliest entry per file (what to restore) and newest "after" hash (what we expect now)
        Map<Path, Entry> first = new LinkedHashMap<>();
        Map<Path, String> expected = new LinkedHashMap<>();
        for (int t : undone) {
            for (Entry e : turns.getOrDefault(t, Map.of()).values()) {
                first.putIfAbsent(e.path(), e);
                if (e.after() != null) {
                    expected.put(e.path(), e.after());
                }
            }
        }
        List<String> gitOps = new ArrayList<>();
        undone.forEach(t -> git.getOrDefault(t, Set.of()).forEach(op -> {
            if (!gitOps.contains(op)) {
                gitOps.add(op);
            }
        }));
        List<String> conflicts = new ArrayList<>();
        try {
            for (Map.Entry<Path, String> x : expected.entrySet()) {
                if (!hashOf(x.getKey()).equals(x.getValue())) {
                    conflicts.add(first.get(x.getKey()).display());
                }
            }
            if (!conflicts.isEmpty() && !force) {
                return new Restore(false, List.of(), conflicts, List.of(), gitOps, undone);
            }
            List<String> restored = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            for (Entry e : first.values()) {
                if (e.tooLarge()) {
                    skipped.add(e.display());
                } else if (!e.existed()) {
                    Files.deleteIfExists(e.path());
                    restored.add(e.display());
                } else {
                    Files.createDirectories(e.path().getParent());
                    Files.write(e.path(), Files.readAllBytes(root.resolve(Integer.toString(turnOf(e))).resolve("blobs").resolve(e.blob())));
                    restored.add(e.display());
                }
            }
            undone.forEach(t -> {
                turns.remove(t);
                git.remove(t);
            });
            var files = Json.array();
            restored.forEach(files::add);
            var turnsJson = Json.array();
            undone.forEach(turnsJson::add);
            var ev = Json.object();
            ev.set("files", files);
            ev.set("turns", turnsJson);
            ev.put("forced", force && !conflicts.isEmpty());
            bus.publish(origin.get(), "checkpoint.restored", ev);
            return new Restore(true, restored, conflicts, skipped, gitOps, undone);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot restore checkpoint", e);
        }
    }

    private int turnOf(Entry e) {
        for (var t : turns.entrySet()) {
            if (t.getValue().get(e.path()) == e) {
                return t.getKey();
            }
        }
        throw new IllegalStateException("entry not found");
    }

    /** The changes made in a turn, as unified diffs of saved versus current content. */
    public synchronized String diff(int turn) {
        Map<Path, Entry> t = turns.get(turn);
        if (t == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try {
            for (Entry e : t.values()) {
                String before = e.blob() == null ? null
                        : Files.readString(root.resolve(Integer.toString(turn)).resolve("blobs").resolve(e.blob()), StandardCharsets.UTF_8);
                if (e.tooLarge()) {
                    sb.append(e.display()).append(": over 10 MB, no diff\n");
                    continue;
                }
                String after = Files.exists(e.path()) ? Files.readString(e.path(), StandardCharsets.UTF_8) : null;
                sb.append(Diffs.unified(e.display(), before, after));
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return sb.toString();
    }

    /** For tests: the last turn with a checkpoint, or 0. */
    synchronized int lastTurn() {
        List<Integer> t = turns();
        return t.isEmpty() ? 0 : t.getLast();
    }

    static JsonNode manifest(Path root, int turn) throws IOException {
        return Json.parse(Files.readString(root.resolve(Integer.toString(turn)).resolve("manifest.json")));
    }
}
