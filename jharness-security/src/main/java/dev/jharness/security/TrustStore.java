package dev.jharness.security;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.jharness.events.Json;

/** Trust answers per folder (D-011, D-043), in {@code ~/.jharness/trusted.json}, keyed by real path. */
public final class TrustStore {

    private final Path file;
    private final ObjectNode projects;

    private TrustStore(Path file, ObjectNode projects) {
        this.file = file;
        this.projects = projects;
    }

    public static Path fileFor(Path home) {
        return home.resolve(".jharness").resolve("trusted.json");
    }

    public static TrustStore load(Path home) {
        Path f = fileFor(home);
        try {
            JsonNode root = Json.parse(Files.readString(f, StandardCharsets.UTF_8));
            JsonNode p = root.path("projects");
            return new TrustStore(f, p.isObject() ? (ObjectNode) p.deepCopy() : Json.object());
        } catch (NoSuchFileException e) {
            return new TrustStore(f, Json.object());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + f, e);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(f + ": " + e.getMessage());
        }
    }

    private static String key(Path workspace) {
        try {
            return workspace.toRealPath().toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The saved answer, or empty if the folder was never asked about. */
    public synchronized Optional<Boolean> trusted(Path workspace) {
        JsonNode n = projects.get(key(workspace));
        return n == null || !n.has("trusted") ? Optional.empty() : Optional.of(n.get("trusted").asBoolean());
    }

    /** Saves the answer (0700 folder, 0600 file, written atomically). */
    public synchronized void record(Path workspace, boolean trusted) {
        projects.putObject(key(workspace)).put("trusted", trusted).put("date", LocalDate.now().toString());
        ObjectNode root = Json.object();
        root.set("projects", projects);
        try {
            Path dir = Files.createDirectories(file.getParent());
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
            Path tmp = Files.createTempFile(dir, "trusted", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(tmp, Json.write(root) + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }
}
