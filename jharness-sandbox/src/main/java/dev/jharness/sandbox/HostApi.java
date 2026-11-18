package dev.jharness.sandbox;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.stream.Stream;

import org.graalvm.polyglot.HostAccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.jharness.events.EventException;
import dev.jharness.events.Json;
import dev.jharness.process.GitOperations;
import dev.jharness.process.OperationException;
import dev.jharness.process.ProcessResult;
import dev.jharness.process.Search;
import dev.jharness.security.Decision;
import dev.jharness.security.FilePolicy;
import dev.jharness.security.Operation;
import dev.jharness.security.Workspace;

/**
 * The host side of {@code api} (constitution Articles II and VI). Every method takes and returns
 * strings and never throws: it returns {@code {"value":"…"}} or {@code {"error":"…"}}, and the JS
 * shim in {@link AgentIsolate} turns errors into JS {@code Error}s. Each call resolves its paths,
 * passes the permission gate, runs, and emits {@code permission.decided} and {@code api.call}.
 */
public final class HostApi {

    static final long MAX_READ_BYTES = 1L << 20;
    static final long MAX_WRITE_BYTES = 10L << 20;

    private final ApiServices s;
    private final RunClock clock;

    HostApi(ApiServices services, RunClock clock) {
        this.s = services;
        this.clock = clock;
    }

    private interface Body {
        String run() throws Exception;
    }

    /** Asks the gate (not counted toward the wall clock), runs the body, emits events, wraps the result. */
    private String call(String api, Operation.Kind kind, String summary, ObjectNode detail, Body body) {
        Decision d;
        clock.pause();
        try {
            d = s.gate().decide(new Operation(api, kind, summary, detail));
        } finally {
            clock.resume();
        }
        s.bus().publish(s.origin().get(), "permission.decided", Json.object().put("api", api).put("kind", kind.wire())
                .put("allowed", d.allowed()).put("reason", d.reason()).put("summary", summary));
        if (!d.allowed()) {
            s.bus().publish(s.origin().get(), "api.call", Json.object().put("api", api).put("status", "denied").put("summary", summary));
            return error("permission denied: " + d.reason());
        }
        try {
            String v = body.run();
            s.bus().publish(s.origin().get(), "api.call", Json.object().put("api", api).put("status", "ok").put("summary", summary));
            return Json.write(Json.object().put("value", v));
        } catch (Exception e) {
            String msg = message(e);
            s.bus().publish(s.origin().get(), "api.call", Json.object().put("api", api).put("status", "error")
                    .put("summary", summary).put("error", msg));
            return error(msg);
        }
    }

    /** For failures before the gate (bad arguments, paths outside the workspace). */
    private String reject(String api, Exception e) {
        String msg = message(e);
        s.bus().publish(s.origin().get(), "api.call", Json.object().put("api", api).put("status", "error").put("summary", api).put("error", msg));
        return error(msg);
    }

    private static String message(Exception e) {
        if (e instanceof Workspace.PathException || e instanceof OperationException || e instanceof EventException
                || e instanceof IllegalArgumentException) {
            return e.getMessage();
        }
        if (e instanceof java.nio.file.NoSuchFileException n) {
            return "no such file: " + n.getFile();
        }
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    private static String error(String msg) {
        return Json.write(Json.object().put("error", msg));
    }

    private static ObjectNode args(String json) {
        if (json == null || json.isBlank() || json.equals("undefined") || json.equals("null")) {
            return Json.object();
        }
        return Json.parseObject(json);
    }

    private static String optText(ObjectNode a, String f) {
        JsonNode n = a.get(f);
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isTextual()) {
            throw new IllegalArgumentException("\"" + f + "\" must be a string");
        }
        return n.asText();
    }

    /** Drops results the deny list hides; a second line of defence behind the search exclusions. */
    private void filter(ObjectNode result, String field, java.util.function.Function<JsonNode, String> path) {
        var arr = (com.fasterxml.jackson.databind.node.ArrayNode) result.get(field);
        for (int i = arr.size() - 1; i >= 0; i--) {
            if (s.files().hidden(path.apply(arr.get(i)))) {
                arr.remove(i);
            }
        }
    }

    private static int max(ObjectNode a) {
        JsonNode n = a.get("max");
        if (n == null) {
            return Search.DEFAULT_MAX;
        }
        if (!n.canConvertToInt() || n.asInt() < 1 || n.asInt() > Search.MAX) {
            throw new IllegalArgumentException("\"max\" must be 1 to " + Search.MAX);
        }
        return n.asInt();
    }

    // ---------------------------------------------------------------------------------------------

    @HostAccess.Export
    public String readContext() {
        return call("readContext", Operation.Kind.READ, "read workspace context", Json.object(), () -> {
            ObjectNode o = Json.object();
            o.put("workspace", s.workspace().root().toString());
            o.put("os", System.getProperty("os.name"));
            o.put("arch", System.getProperty("os.arch"));
            o.put("date", LocalDate.now().toString());
            if (s.git() != null && Files.isDirectory(s.workspace().root().resolve(".git"))) {
                ProcessResult st = s.git().run(Json.object().put("op", "status"));
                if (st.exitCode() == 0) {
                    String[] lines = st.output().split("\n");
                    String branch = lines.length > 0 && lines[0].startsWith("## ") ? lines[0].substring(3) : "";
                    branch = branch.replaceFirst("^No commits yet on ", "");
                    int dots = branch.indexOf("...");
                    branch = dots >= 0 ? branch.substring(0, dots) : branch.replaceFirst(" .*", "");
                    boolean dirty = java.util.Arrays.stream(lines).skip(1).anyMatch(l -> !l.isBlank());
                    o.putObject("git").put("branch", branch).put("dirty", dirty);
                } else {
                    o.putNull("git");
                }
            } else {
                o.putNull("git");
            }
            var mounts = o.putArray("mounts");
            s.files().mounts().forEach(m -> mounts.add(m.toString()));
            var files = o.putArray("files");
            try (Stream<Path> st = Files.list(s.workspace().root())) {
                st.filter(p -> !s.files().hidden(p.getFileName().toString()))
                        .map(p -> p.getFileName().toString() + (Files.isDirectory(p) ? "/" : "")).sorted().limit(200).forEach(files::add);
            }
            return Json.write(o);
        });
    }

    @HostAccess.Export
    public String readFile(String path) {
        Path p;
        String rel;
        try {
            FilePolicy.Target t = s.files().read(path);
            p = t.path();
            rel = t.display();
        } catch (RuntimeException e) {
            return reject("readFile", e);
        }
        return call("readFile", Operation.Kind.READ, "read " + rel, Json.object().put("path", rel), () -> {
            if (!Files.isRegularFile(p)) {
                throw new IllegalArgumentException(Files.isDirectory(p) ? rel + " is a folder" : "no such file: " + rel);
            }
            long size = Files.size(p);
            if (size > MAX_READ_BYTES) {
                throw new IllegalArgumentException(rel + " is " + size + " bytes; readFile reads up to " + MAX_READ_BYTES);
            }
            try {
                return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(p))).toString();
            } catch (CharacterCodingException e) {
                throw new IllegalArgumentException(rel + " is not UTF-8 text");
            }
        });
    }

    @HostAccess.Export
    public String writeFile(String path, String content) {
        Path p;
        String rel;
        try {
            FilePolicy.Target t = s.files().write(path);
            p = t.path();
            rel = t.display();
            if (content == null) {
                throw new IllegalArgumentException("content is missing");
            }
        } catch (RuntimeException e) {
            return reject("writeFile", e);
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return call("writeFile", Operation.Kind.WRITE, "write " + rel + " (" + bytes.length + " bytes)",
                Json.object().put("path", rel).put("bytes", bytes.length), () -> {
                    if (bytes.length > MAX_WRITE_BYTES) {
                        throw new IllegalArgumentException("content is " + bytes.length + " bytes; the limit is " + MAX_WRITE_BYTES);
                    }
                    if (Files.isDirectory(p)) {
                        throw new IllegalArgumentException(rel + " is a folder");
                    }
                    Files.createDirectories(p.getParent());
                    Files.write(p, bytes);
                    return Json.write(Json.object().put("path", rel).put("bytes", bytes.length));
                });
    }

    @HostAccess.Export
    public String editFile(String path, String oldText, String newText) {
        Path p;
        String rel;
        try {
            FilePolicy.Target t = s.files().write(path);
            p = t.path();
            rel = t.display();
            if (oldText == null || oldText.isEmpty() || newText == null) {
                throw new IllegalArgumentException("editFile needs a non-empty oldText and a newText");
            }
        } catch (RuntimeException e) {
            return reject("editFile", e);
        }
        return call("editFile", Operation.Kind.WRITE, "edit " + rel, Json.object().put("path", rel), () -> {
            if (!Files.isRegularFile(p)) {
                throw new IllegalArgumentException("no such file: " + rel);
            }
            if (Files.size(p) > MAX_WRITE_BYTES) {
                throw new IllegalArgumentException(rel + " is too large to edit");
            }
            String text = Files.readString(p, StandardCharsets.UTF_8);
            int first = text.indexOf(oldText);
            if (first < 0) {
                throw new IllegalArgumentException("oldText was not found in " + rel);
            }
            if (text.indexOf(oldText, first + 1) >= 0) {
                int n = 0;
                for (int i = text.indexOf(oldText); i >= 0; i = text.indexOf(oldText, i + 1)) {
                    n++;
                }
                throw new IllegalArgumentException("oldText occurs " + n + " times in " + rel + "; include more context so it occurs once");
            }
            Files.writeString(p, text.substring(0, first) + newText + text.substring(first + oldText.length()), StandardCharsets.UTF_8);
            return Json.write(Json.object().put("path", rel).put("replacements", 1));
        });
    }

    @HostAccess.Export
    public String listFiles(String json) {
        ObjectNode a;
        Path dir;
        String rel;
        int max;
        String glob;
        try {
            a = args(json);
            FilePolicy.Target t = s.files().read(a.has("path") ? optText(a, "path") : ".");
            dir = t.path();
            rel = t.display();
            glob = optText(a, "glob");
            max = max(a);
        } catch (RuntimeException e) {
            return reject("listFiles", e);
        }
        return call("listFiles", Operation.Kind.READ, "list " + rel + (glob == null ? "" : " " + glob), a, () -> {
            if (!Files.isDirectory(dir)) {
                throw new IllegalArgumentException(rel + " is not a folder");
            }
            ObjectNode r = s.search().listFiles(dir, glob, max);
            filter(r, "files", n -> n.asText());
            return Json.write(r);
        });
    }

    @HostAccess.Export
    public String grep(String json) {
        ObjectNode a;
        Path dir;
        String rel;
        String pattern;
        String glob;
        int max;
        boolean ignoreCase;
        try {
            a = args(json);
            pattern = optText(a, "pattern");
            if (pattern == null || pattern.isEmpty()) {
                throw new IllegalArgumentException("grep needs a \"pattern\"");
            }
            FilePolicy.Target t = s.files().read(a.has("path") ? optText(a, "path") : ".");
            dir = t.path();
            rel = t.display();
            glob = optText(a, "glob");
            max = max(a);
            JsonNode ic = a.get("ignoreCase");
            if (ic != null && !ic.isBoolean()) {
                throw new IllegalArgumentException("\"ignoreCase\" must be true or false");
            }
            ignoreCase = ic != null && ic.asBoolean();
        } catch (RuntimeException e) {
            return reject("grep", e);
        }
        return call("grep", Operation.Kind.READ, "grep " + pattern + " in " + rel, a, () -> {
            ObjectNode r = s.search().grep(pattern, dir, glob, ignoreCase, max);
            filter(r, "matches", n -> n.path("path").asText());
            return Json.write(r);
        });
    }

    @HostAccess.Export
    public String git(String json) {
        ObjectNode a;
        Operation.Kind kind;
        try {
            if (s.git() == null) {
                throw new IllegalArgumentException("git is not installed");
            }
            a = args(json);
            kind = GitOperations.kind(a);
            for (JsonNode p : a.path("paths")) {
                // add and restore change files; every path must pass the file policy.
                if (kind == Operation.Kind.GIT_WRITE) {
                    s.files().write(p.asText());
                } else {
                    s.files().read(p.asText());
                }
            }
        } catch (RuntimeException e) {
            return reject("git", e);
        }
        return call("git", kind, "git " + a.path("op").asText(), a, () -> GitOperations.toJson(s.git().run(a)));
    }

    @HostAccess.Export
    public String emit(String json) {
        return call("emit", Operation.Kind.EMIT, "emit event", Json.object(),
                () -> s.bus().publishGuest(s.origin().get(), json).id());
    }
}
