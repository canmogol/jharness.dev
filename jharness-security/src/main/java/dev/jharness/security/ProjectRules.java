package dev.jharness.security;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.jharness.events.Json;
import dev.jharness.events.SessionLog;

/**
 * "Always for this project" approvals (D-040), in
 * {@code <home>/.jharness/projects/<project-hash>/permissions.json}. Only this file adds allow rules;
 * nothing in the repository can.
 */
public final class ProjectRules {

    /**
     * @param key the path for writes, the git operation for git writes, {@code "<tool> <op>"} for builds,
     *     {@code "<METHOD> <host>"} for HTTP writes
     */
    public record Rule(Operation.Kind kind, String key) {

        ObjectNode toJson() {
            ObjectNode o = Json.object().put("kind", kind.wire());
            o.put(kind == Operation.Kind.WRITE ? "path" : "op", key);
            return o;
        }
    }

    private final Path file;
    private final List<Rule> rules = new ArrayList<>();

    private ProjectRules(Path file) {
        this.file = file;
    }

    public static Path fileFor(Path home, Path workspace) {
        return home.resolve(".jharness").resolve("projects").resolve(SessionLog.projectHash(workspace)).resolve("permissions.json");
    }

    public static ProjectRules load(Path home, Path workspace) {
        ProjectRules r = new ProjectRules(fileFor(home, workspace));
        String text;
        try {
            text = Files.readString(r.file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return r;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + r.file, e);
        }
        JsonNode root;
        try {
            root = Json.parse(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(r.file + ": " + e.getMessage());
        }
        for (JsonNode n : root.path("allow")) {
            String kind = n.path("kind").asText();
            if (kind.equals("write") && n.path("path").isTextual()) {
                r.rules.add(new Rule(Operation.Kind.WRITE, n.get("path").asText()));
            } else if (kind.equals("git-write") && n.path("op").isTextual()) {
                r.rules.add(new Rule(Operation.Kind.GIT_WRITE, n.get("op").asText()));
            } else if (kind.equals("build") && n.path("op").isTextual()) {
                r.rules.add(new Rule(Operation.Kind.BUILD, n.get("op").asText()));
            } else if (kind.equals("http-write") && n.path("op").isTextual()) {
                r.rules.add(new Rule(Operation.Kind.HTTP_WRITE, n.get("op").asText()));
            } else {
                throw new IllegalArgumentException(r.file + ": unknown rule " + n);
            }
        }
        return r;
    }

    public Path file() {
        return file;
    }

    public synchronized List<Rule> rules() {
        return List.copyOf(rules);
    }

    /** The rule that would cover this operation, or null if it can't be made permanent. */
    public static Rule ruleFor(Operation op) {
        return switch (op.kind()) {
            case WRITE -> op.detail().hasNonNull("path") ? new Rule(Operation.Kind.WRITE, op.detail().get("path").asText()) : null;
            case GIT_WRITE -> op.detail().hasNonNull("op") ? new Rule(Operation.Kind.GIT_WRITE, op.detail().get("op").asText()) : null;
            case BUILD -> op.detail().hasNonNull("op") ? new Rule(Operation.Kind.BUILD, op.detail().get("op").asText()) : null;
            case HTTP_WRITE -> op.detail().hasNonNull("op") ? new Rule(Operation.Kind.HTTP_WRITE, op.detail().get("op").asText()) : null;
            default -> null;
        };
    }

    public synchronized boolean allows(Operation op) {
        Rule r = ruleFor(op);
        return r != null && rules.contains(r);
    }

    /** Adds the rule and writes the file (0700 folders, 0600 file, written atomically). */
    public synchronized void add(Rule rule) {
        if (rules.contains(rule)) {
            return;
        }
        rules.add(rule);
        ObjectNode root = Json.object();
        ArrayNode allow = root.putArray("allow");
        rules.forEach(x -> allow.add(x.toJson()));
        try {
            Path dir = file.getParent();
            Files.createDirectories(dir);
            Path base = dir;
            while (base != null && base.getFileName() != null && !base.getFileName().toString().equals(".jharness")) {
                Files.setPosixFilePermissions(base, PosixFilePermissions.fromString("rwx------"));
                base = base.getParent();
            }
            if (base != null) {
                Files.setPosixFilePermissions(base, PosixFilePermissions.fromString("rwx------"));
            }
            Path tmp = Files.createTempFile(dir, "permissions", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(tmp, Json.write(root) + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }
}
