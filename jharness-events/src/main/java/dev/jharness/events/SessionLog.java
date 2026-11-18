package dev.jharness.events;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
 * Appends every event as one JSON line to
 * {@code <home>/.jharness/sessions/<project-hash>/<session-id>.jsonl} (D-038). Folders are 0700 and the
 * file 0600. Each line is written and forced to the file before {@link #accept} returns.
 */
public final class SessionLog implements Consumer<Event>, AutoCloseable {

    private static final Set<PosixFilePermission> DIR = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE = PosixFilePermissions.fromString("rw-------");

    private final Path path;
    private final FileChannel channel;

    private SessionLog(Path path, FileChannel channel) {
        this.path = path;
        this.channel = channel;
    }

    /** First 16 hex characters of the SHA-256 of the workspace's real path. */
    public static String projectHash(Path workspace) {
        try {
            String real = workspace.toRealPath().toString();
            byte[] d = MessageDigest.getInstance("SHA-256").digest(real.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 16);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Path pathFor(Path home, Path workspace, String session) {
        return home.resolve(".jharness").resolve("sessions").resolve(projectHash(workspace)).resolve(session + ".jsonl");
    }

    public static SessionLog open(Path home, Path workspace, String session) throws IOException {
        Path file = pathFor(home, workspace, session);
        createPrivateDirs(home, file.getParent());
        boolean posix = posix();
        FileAttribute<?>[] attrs = posix
                ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(FILE)}
                : new FileAttribute<?>[0];
        FileChannel ch = FileChannel.open(file,
                Set.of(StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE), attrs);
        if (posix) {
            Files.setPosixFilePermissions(file, FILE);
        }
        return new SessionLog(file, ch);
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
        byte[] line = (event.toJsonLine() + "\n").getBytes(StandardCharsets.UTF_8);
        try {
            ByteBuffer b = ByteBuffer.wrap(line);
            while (b.hasRemaining()) {
                channel.write(b);
            }
            channel.force(false);
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
        channel.close();
    }
}
