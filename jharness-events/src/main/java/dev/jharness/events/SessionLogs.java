package dev.jharness.events;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Finds session logs under {@code ~/.jharness/sessions} (D-038) for {@code jharness log} (spec 021). */
public final class SessionLogs {

    /** What a session argument may be when it isn't a path: no separators, so it can't leave the logs folder (AC-06). */
    public static final Pattern ARG = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private static final Pattern ID_TIME = Pattern.compile("s-([0-9a-z]+)-[0-9a-f]+");

    /** A log file and when its session started. */
    public record Entry(String id, Path file, Instant started) {}

    /** The session argument names no log, or more than one. */
    public static final class LookupException extends Exception {
        public LookupException(String message) {
            super(message);
        }
    }

    private SessionLogs() {}

    public static Path root(Path home) {
        return home.resolve(".jharness").resolve("sessions");
    }

    /** This workspace's logs, newest first. */
    public static List<Entry> project(Path home, Path workspace) throws IOException {
        return entries(root(home).resolve(SessionLog.projectHash(workspace)));
    }

    /** Every project's logs, newest first. */
    public static List<Entry> all(Path home) throws IOException {
        List<Entry> out = new ArrayList<>();
        Path root = root(home);
        if (Files.isDirectory(root)) {
            try (Stream<Path> dirs = Files.list(root)) {
                for (Path d : dirs.filter(Files::isDirectory).toList()) {
                    out.addAll(entries(d));
                }
            }
        }
        out.sort(NEWEST_FIRST);
        return out;
    }

    private static final Comparator<Entry> NEWEST_FIRST = Comparator.comparing(Entry::started).reversed().thenComparing(Entry::id);

    private static List<Entry> entries(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Entry> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".jsonl") && Files.isRegularFile(p)).toList()) {
                String name = f.getFileName().toString();
                String id = name.substring(0, name.length() - ".jsonl".length());
                out.add(new Entry(id, f, started(id, f)));
            }
        }
        out.sort(NEWEST_FIRST);
        return out;
    }

    /** From the id's base-36 milliseconds, else the file's modification time. */
    static Instant started(String id, Path f) throws IOException {
        Matcher m = ID_TIME.matcher(id);
        if (m.matches()) {
            try {
                return Instant.ofEpochMilli(Long.parseLong(m.group(1), 36));
            } catch (NumberFormatException e) {
                // Falls through to the file time.
            }
        }
        return Files.getLastModifiedTime(f).toInstant();
    }

    /**
     * A path to a {@code .jsonl} file; {@code last}, the newest log of this workspace; or an id or unique
     * prefix, looked up in this workspace and then in every project.
     */
    public static Path resolve(Path home, Path workspace, String arg) throws IOException, LookupException {
        if (arg.endsWith(".jsonl") || arg.contains("/") || arg.contains("\\")) {
            Path p = Path.of(arg).toAbsolutePath();
            if (!Files.isRegularFile(p)) {
                throw new LookupException("no such file: " + p);
            }
            return p;
        }
        if (!ARG.matcher(arg).matches()) {
            throw new LookupException("not a session id: use letters, digits and '-', or a path to a .jsonl file");
        }
        List<Entry> here = project(home, workspace);
        if (arg.equals("last")) {
            if (here.isEmpty()) {
                throw new LookupException("no sessions for " + workspace);
            }
            return here.getFirst().file();
        }
        List<Entry> m = matching(here, arg);
        if (m.isEmpty()) {
            m = matching(all(home), arg);
        }
        if (m.isEmpty()) {
            throw new LookupException("no session " + arg);
        }
        if (m.size() > 1) {
            List<String> ids = m.stream().limit(10).map(Entry::id).toList();
            throw new LookupException(arg + " matches " + m.size() + " sessions: " + String.join(", ", ids) + (m.size() > 10 ? ", …" : ""));
        }
        return m.getFirst().file();
    }

    private static List<Entry> matching(List<Entry> entries, String arg) {
        for (Entry e : entries) {
            if (e.id().equals(arg)) {
                return List.of(e);
            }
        }
        return entries.stream().filter(e -> e.id().startsWith(arg)).toList();
    }
}
