package dev.jharness.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;


/**
 * Local crash reports (spec 021 AC-08): a text file per crash in {@code ~/.jharness/crash/}, folder 0700
 * and file 0600, never sent anywhere. Event bodies stay out; only the time, turn and type of recent events
 * go in.
 */
final class CrashReport {

    /** Reports kept; older ones are deleted when a new one is written. */
    static final int KEEP = 20;

    /** Recent events listed in a report. */
    static final int RECENT = 30;

    static final String NOTE = "Check this report before sharing it: exception messages can quote your files, prompts or paths.";

    private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private CrashReport() {}

    /** What the report says about where the crash happened; any field may be null. */
    record Context(String session, Path log) {
        static final Context NONE = new Context(null, null);
    }

    /** The time, turn and type of the log's last {@value #RECENT} events; empty if it can't be read. */
    static List<String> recent(Path log) {
        if (log == null) {
            return List.of();
        }
        ArrayDeque<String> ring = new ArrayDeque<>();
        try (var lines = Files.lines(log, StandardCharsets.UTF_8)) {
            lines.forEach(l -> {
                try {
                    var e = dev.jharness.events.Json.parse(l);
                    if (ring.size() == RECENT) {
                        ring.removeFirst();
                    }
                    ring.addLast(e.path("ts").asText() + "  t" + e.path("turn").asInt() + "  " + TerminalSafe.escape(e.path("type").asText()));
                } catch (RuntimeException ignored) {
                    // A torn last line.
                }
            });
        } catch (IOException | java.io.UncheckedIOException e) {
            return List.of();
        }
        return List.copyOf(ring);
    }

    static Path dir(Path home) {
        return home.resolve(".jharness").resolve("crash");
    }

    /** Writes the report and prunes old ones; returns its path. */
    static Path write(Path home, Throwable t, Thread thread, Context c, Instant now) throws IOException {
        Path dir = dir(home);
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        for (Path p : List.of(home.resolve(".jharness"), dir)) {
            if (!Files.isDirectory(p)) {
                if (posix) {
                    Files.createDirectories(p, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                } else {
                    Files.createDirectories(p);
                }
            }
        }
        if (posix) {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        }
        String base = "crash-" + NAME.format(now) + (c.session() != null ? "-" + c.session() : "");
        String text = text(home, t, thread, c, now);
        Path file = null;
        for (int i = 0; file == null; i++) {
            Path f = dir.resolve(base + (i == 0 ? "" : "-" + i) + ".txt");
            try {
                if (posix) {
                    Files.createFile(f, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                } else {
                    Files.createFile(f);
                }
                file = f;
            } catch (FileAlreadyExistsException e) {
                if (i > 100) {
                    throw e;
                }
            }
        }
        Files.writeString(file, text, StandardCharsets.UTF_8);
        prune(dir);
        return file;
    }

    /** Keeps the newest {@value #KEEP} reports. Names start with the UTC time, so they sort by age. */
    static void prune(Path dir) throws IOException {
        List<Path> reports;
        try (Stream<Path> s = Files.list(dir)) {
            reports = s.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("crash-") && n.endsWith(".txt");
            }).sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed()).toList();
        }
        for (Path old : reports.subList(Math.min(KEEP, reports.size()), reports.size())) {
            Files.deleteIfExists(old);
        }
    }

    static String text(Path home, Throwable t, Thread thread, Context c, Instant now) {
        StringBuilder b = new StringBuilder();
        b.append("JHarness crash report\n").append(NOTE).append("\n\n");
        b.append("version: ").append(Main.VERSION).append('\n');
        b.append("time: ").append(now.truncatedTo(ChronoUnit.SECONDS)).append('\n');
        b.append("os: ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version")).append(' ')
                .append(System.getProperty("os.arch")).append('\n');
        b.append("runtime: ").append(runtime()).append('\n');
        b.append("thread: ").append(thread == null ? "?" : thread.getName()).append('\n');
        b.append("session: ").append(c.session() == null ? "none" : c.session()).append('\n');
        if (c.log() != null) {
            b.append("session log: ").append(c.log()).append('\n');
        }
        StringWriter trace = new StringWriter();
        t.printStackTrace(new PrintWriter(trace));
        b.append("\nException:\n").append(trace);
        List<String> recent = recent(c.log());
        if (!recent.isEmpty()) {
            b.append("\nLast events (time, turn, type; bodies left out):\n");
            recent.forEach(r -> b.append(r).append('\n'));
        }
        String home1 = home.toAbsolutePath().normalize().toString();
        String out = b.toString();
        return home1.length() > 1 ? out.replace(home1, "~") : out;
    }

    static String runtime() {
        if ("runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"))) {
            return "native image";
        }
        return "JVM " + System.getProperty("java.version") + " (" + System.getProperty("java.vm.name") + ")";
    }

    /** {@code IllegalStateException: message}, on one line. */
    static String summary(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null || m.isBlank() ? "" : ": " + m.lines().findFirst().orElse(""));
    }
}
