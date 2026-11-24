package dev.jharness.events;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Appends every event but the live stream chunks ({@link #LIVE_ONLY}, D-046) as one JSON line to
 * {@code <home>/.jharness/sessions/<project-folder>/<session-id>.jsonl} (D-038, D-070). Folders are 0700 and the
 * file 0600. Each line is written and forced to the file before {@link #accept} returns.
 */
public final class SessionLog implements Consumer<Event>, AutoCloseable {

    /** Streamed chunks: live subscribers see them; the log has the whole text on {@code model.completed} (D-046). */
    public static final Set<String> LIVE_ONLY = Set.of("model.delta", "model.thinking_delta");

    private static final Set<PosixFilePermission> DIR = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE = PosixFilePermissions.fromString("rw-------");

    private final Path path;
    /** A plain stream, not a FileChannel: an interrupted thread (Esc) must not close the log. */
    private final FileOutputStream out;

    private SessionLog(Path path, FileOutputStream out) {
        this.path = path;
        this.out = out;
    }

    /** Longest slug part of a project folder name. */
    static final int SLUG_CHARS = 80;
    /** A project folder name: a slug, '-', and 7 hex characters. */
    public static final java.util.regex.Pattern FOLDER = java.util.regex.Pattern.compile("[a-z0-9.-]{1,80}-[0-9a-f]{7}");

    /**
     * The folder for a workspace under {@code sessions/}, {@code projects/} and {@code checkpoints/} (D-070):
     * its real path as a lower-case slug, then the first 7 hex characters of the path's SHA-256, such as
     * {@code users-xyz-projects-jharness.dev-2787870}.
     */
    public static String projectFolder(Path workspace) {
        try {
            return folderFor(workspace.toRealPath().toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String folderFor(String real) {
        String slug = real.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9.]+", "-").replaceAll("^[-.]+|[-.]+$", "");
        if (slug.length() > SLUG_CHARS) {
            // The end of a path names the project, so a long slug keeps its last characters.
            slug = slug.substring(slug.length() - SLUG_CHARS).replaceAll("^[-.]+", "");
        }
        if (slug.isEmpty()) {
            slug = "root";
        }
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(real.getBytes(StandardCharsets.UTF_8));
            return slug + "-" + HexFormat.of().formatHex(d).substring(0, 7);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Path pathFor(Path home, Path workspace, String session) {
        return home.resolve(".jharness").resolve("sessions").resolve(projectFolder(workspace)).resolve(session + ".jsonl");
    }

    public static SessionLog open(Path home, Path workspace, String session) throws IOException {
        Path file = pathFor(home, workspace, session);
        createPrivateDirs(home, file.getParent());
        boolean posix = posix();
        FileAttribute<?>[] attrs = posix
                ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(FILE)}
                : new FileAttribute<?>[0];
        if (!Files.exists(file)) {
            Files.createFile(file, attrs);
        }
        if (posix) {
            Files.setPosixFilePermissions(file, FILE);
        }
        return new SessionLog(file, new FileOutputStream(file.toFile(), true));
    }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    /** Creates each missing folder from {@code .jharness} down with mode 0700. */
    private static void createPrivateDirs(Path home, Path dir) throws IOException {
        Path base = home.resolve(".jharness");
        List<Path> chain = new ArrayList<>();
        for (Path p = dir; p != null && p.startsWith(base); p = p.getParent()) {
            chain.addFirst(p);
        }
        for (Path p : chain) {
            if (!Files.isDirectory(p)) {
                if (posix()) {
                    Files.createDirectories(p, PosixFilePermissions.asFileAttribute(DIR));
                    Files.setPosixFilePermissions(p, DIR);
                } else {
                    Files.createDirectories(p);
                }
            }
        }
    }

    public Path path() {
        return path;
    }

    @Override
    public synchronized void accept(Event event) {
        if (LIVE_ONLY.contains(event.type())) {
            return;
        }
        byte[] line = (event.toJsonLine() + "\n").getBytes(StandardCharsets.UTF_8);
        try {
            out.write(line);
            out.getFD().sync();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write session log " + path, e);
        }
    }

    /** Reads a session log back, one JSON object per line. */
    public static List<JsonNode> read(Path file) throws IOException {
        List<JsonNode> out = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                out.add(Json.parse(line));
            }
        }
        return out;
    }

    @Override
    public synchronized void close() throws IOException {
        out.close();
    }
}
