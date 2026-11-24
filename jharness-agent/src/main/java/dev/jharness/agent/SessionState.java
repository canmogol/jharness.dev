package dev.jharness.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.jharness.events.Json;
import dev.jharness.events.SessionLog;
import dev.jharness.llm.ChatMessage;

/**
 * What {@code --resume} and {@code --fork} start from (spec 043, D-081): the main agent's conversation at the end of
 * its last turn, in {@code <session-id>.state.json} next to the session log, mode 0600, replaced atomically.
 *
 * @param turn the last turn number
 * @param compactedThrough turns up to this one are in a summary (D-077)
 * @param forkedFrom the session this one was forked from, or null
 * @param updated when it was written, ISO-8601
 */
public record SessionState(String session, String workspace, String profile, String model, int turn, List<ChatMessage> conversation,
        int compactedThrough, String forkedFrom, String updated, String firstPrompt) {

    public static final int VERSION = 1;

    public SessionState {
        conversation = List.copyOf(conversation);
    }

    public static Path pathFor(Path home, Path workspace, String session) {
        return SessionLog.pathFor(home, workspace, session).resolveSibling(session + ".state.json");
    }

    /** Writes the state: a private temporary file, then a move over the old one. */
    public void save(Path home, Path workspace) throws IOException {
        Path file = pathFor(home, workspace, session);
        Files.createDirectories(file.getParent());
        ObjectNode o = Json.object().put("version", VERSION).put("session", session).put("workspace", this.workspace)
                .put("profile", profile).put("model", model).put("turn", turn).put("compactedThrough", compactedThrough)
                .put("forkedFrom", forkedFrom).put("updated", updated).put("firstPrompt", firstPrompt);
        var m = o.putArray("conversation");
        conversation.forEach(c -> m.addObject().put("role", c.role()).put("content", c.content()));
        Path tmp = Files.createTempFile(file.getParent(), "." + session, ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            Files.writeString(tmp, Json.write(o), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    static SessionState parse(String text) throws IOException {
        JsonNode o;
        try {
            o = Json.parse(text);
        } catch (RuntimeException e) {
            throw new IOException("is not valid JSON");
        }
        if (o.path("version").asInt() != VERSION) {
            throw new IOException("has an unknown version " + o.path("version"));
        }
        List<ChatMessage> conv = new ArrayList<>();
        for (JsonNode m : o.path("conversation")) {
            String role = m.path("role").asText();
            if (!role.equals("user") && !role.equals("assistant") || !m.path("content").isTextual()) {
                throw new IOException("has a message that isn't a user or assistant text");
            }
            conv.add(new ChatMessage(role, m.get("content").asText()));
        }
        String session = o.path("session").asText("");
        if (!session.matches("s-[0-9A-Za-z-]+")) {
            throw new IOException("has no session id");
        }
        return new SessionState(session, o.path("workspace").asText(""), text(o, "profile"), text(o, "model"), o.path("turn").asInt(),
                conv, o.path("compactedThrough").asInt(), text(o, "forkedFrom"), o.path("updated").asText(""), text(o, "firstPrompt"));
    }

    private static String text(JsonNode o, String key) {
        JsonNode v = o.get(key);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** The saved sessions of a workspace, newest first. Unreadable files are skipped. */
    public static List<SessionState> list(Path home, Path workspace) throws IOException {
        Path dir = SessionLog.pathFor(home, workspace, "x").getParent();
        List<SessionState> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.state.json")) {
            for (Path p : ds) {
                try {
                    out.add(parse(Files.readString(p, StandardCharsets.UTF_8)));
                } catch (IOException | RuntimeException ignored) {
                    // Not one we can resume.
                }
            }
        }
        out.sort(Comparator.comparing(SessionState::updated).reversed());
        return out;
    }

    /**
     * The session to resume or fork: {@code last} (or null) for the newest, else an id or a unique start of one.
     *
     * @throws IOException with a message for the user when there is none, or more than one matches
     */
    public static SessionState find(Path home, Path workspace, String ref) throws IOException {
        Path real = workspace.toRealPath();
        List<SessionState> all = list(home, real);
        if (all.isEmpty()) {
            throw new IOException("no session to resume in " + real + " (a session is saved when its first turn ends)");
        }
        SessionState s;
        if (ref == null || ref.equals("last")) {
            s = all.getFirst();
        } else {
            List<SessionState> m = all.stream().filter(x -> x.session().equals(ref)).toList();
            if (m.isEmpty()) {
                m = all.stream().filter(x -> x.session().startsWith(ref)).toList();
            }
            if (m.isEmpty()) {
                throw new IOException("no session " + ref + " in " + real + "; jharness log lists them");
            }
            if (m.size() > 1) {
                throw new IOException(ref + " matches " + m.size() + " sessions: "
                        + String.join(", ", m.stream().map(SessionState::session).limit(5).toList()));
            }
            s = m.getFirst();
        }
        if (!s.workspace().equals(real.toString())) {
            throw new IOException("session " + s.session() + " belongs to " + s.workspace() + ", not " + real);
        }
        return s;
    }

    /** {@code --resume} continues the session (same id and log); {@code --fork} starts a new one from a copy. */
    public record Resume(SessionState state, boolean fork) {}
}
