package dev.jharness.process;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import dev.jharness.process.ToolDefinition.DefinitionException;
import dev.jharness.process.ToolDefinition.Source;

/**
 * The tools of a session (spec 028, D-066): {@code ~/.jharness/tools/*.json}, then, when the project is
 * trusted and its tools are confirmed, {@code .jharness/tools/*.json} in the workspace. A project file
 * replaces the user's tool of the same name; {@code {"disabled": true}} removes it.
 */
public final class ToolDefinitions {

    /** The shipped defaults, built from {@code examples/.jharness/tools/}. */
    public static final List<String> DEFAULTS = List.of("go", "gofmt", "gradle", "maven", "npm");
    static final long MAX_FILE_BYTES = 64 * 1024;

    /** A project tool for the trust question: name, executable, operations, and the user's tool it replaces. */
    public record ProjectTool(String name, String executable, List<String> operations, boolean replaces, boolean disabled, String error,
            String hash) {

        public String line() {
            if (error != null) {
                return name + ": " + error;
            }
            if (disabled) {
                return name + ": turns off your " + name + " tool";
            }
            return name + ": runs " + executable + " (" + String.join(", ", operations) + ")" + (replaces ? ", replaces yours" : "");
        }
    }

    private final Map<String, ToolDefinition> tools;
    private final Map<String, String> errors;
    private final List<ProjectTool> project;

    private ToolDefinitions(Map<String, ToolDefinition> tools, Map<String, String> errors, List<ProjectTool> project) {
        this.tools = Collections.unmodifiableMap(tools);
        this.errors = Collections.unmodifiableMap(errors);
        this.project = List.copyOf(project);
    }

    public static Path userFolder(Path home) {
        return home.resolve(".jharness").resolve("tools");
    }

    public static Path projectFolder(Path workspace) {
        return workspace.resolve(".jharness").resolve("tools");
    }

    /** The text of a shipped default, such as {@code maven}. */
    public static String defaultText(String name) {
        try (InputStream in = ToolDefinitions.class.getResourceAsStream("tools/" + name + ".json")) {
            if (in == null) {
                throw new IllegalArgumentException("no shipped tool " + name + " (shipped: " + String.join(", ", DEFAULTS) + ")");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The shipped defaults alone, as if just copied into the user's folder; for tests and {@code jharness tools}. */
    public static ToolDefinitions defaults() {
        Map<String, ToolDefinition> m = new TreeMap<>();
        for (String n : DEFAULTS) {
            String text = defaultText(n);
            try {
                m.put(n, ToolDefinition.parse(n, text, Source.USER, "~/.jharness/tools/" + n + ".json", sha256(text.getBytes(StandardCharsets.UTF_8))));
            } catch (DefinitionException e) {
                throw new IllegalStateException("shipped tool " + n + " is invalid: " + e.getMessage(), e);
            }
        }
        return new ToolDefinitions(m, Map.of(), List.of());
    }

    /**
     * Creates {@code ~/.jharness/tools/} with the shipped defaults when it doesn't exist (D-066). After
     * that the folder is the user's: nothing here rewrites or re-adds a file.
     *
     * @return the files written; empty when the folder already existed
     */
    public static List<Path> seed(Path home) {
        Path dir = userFolder(home);
        if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try {
            Files.createDirectories(dir.getParent());
            Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            List<Path> out = new ArrayList<>();
            for (String n : DEFAULTS) {
                out.add(write(dir, n, defaultText(n)));
            }
            return out;
        } catch (java.nio.file.FileAlreadyExistsException e) {
            return List.of();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create " + dir, e);
        }
    }

    /** {@code jharness tools reset NAME}: writes the shipped default again. */
    public static Path reset(Path home, String name) {
        String text = defaultText(name);
        Path dir = userFolder(home);
        try {
            if (!Files.isDirectory(dir)) {
                Files.createDirectories(dir.getParent());
                Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            }
            return write(dir, name, text);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + dir.resolve(name + ".json"), e);
        }
    }

    private static Path write(Path dir, String name, String text) throws IOException {
        Path tmp = Files.createTempFile(dir, name, ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        Path f = dir.resolve(name + ".json");
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return f;
    }

    /**
     * The session's tools.
     *
     * @param useProject whether the project's tools count: trusted, and confirmed by the user (D-066)
     */
    public static ToolDefinitions load(Path home, Path workspace, boolean useProject) {
        Map<String, ToolDefinition> tools = new TreeMap<>();
        Map<String, String> errors = new TreeMap<>();
        for (var e : read(userFolder(home), Source.USER, "~/.jharness/tools/").entrySet()) {
            if (e.getValue() instanceof ToolDefinition d) {
                if (!d.disabled()) {
                    tools.put(e.getKey(), d);
                }
            } else {
                errors.put(e.getKey(), (String) e.getValue());
            }
        }
        List<ProjectTool> project = new ArrayList<>();
        if (useProject) {
            for (ProjectTool p : describeProject(home, workspace)) {
                project.add(p);
            }
            for (var e : read(projectFolder(workspace), Source.PROJECT, ".jharness/tools/").entrySet()) {
                errors.remove(e.getKey());
                if (e.getValue() instanceof ToolDefinition d) {
                    if (d.disabled()) {
                        tools.remove(e.getKey());
                    } else {
                        tools.put(e.getKey(), d);
                    }
                } else {
                    tools.remove(e.getKey());
                    errors.put(e.getKey(), (String) e.getValue());
                }
            }
        }
        return new ToolDefinitions(tools, errors, project);
    }

    /**
     * The project's tool files, read even when the project isn't trusted yet, so the trust question
     * can list them.
     */
    public static List<ProjectTool> describeProject(Path home, Path workspace) {
        Map<String, Object> user = read(userFolder(home), Source.USER, "~/.jharness/tools/");
        List<ProjectTool> out = new ArrayList<>();
        Path dir = projectFolder(workspace);
        for (var e : read(dir, Source.PROJECT, ".jharness/tools/").entrySet()) {
            String hash = hashOf(dir.resolve(e.getKey() + ".json"));
            boolean replaces = user.get(e.getKey()) instanceof ToolDefinition u && !u.disabled();
            if (e.getValue() instanceof ToolDefinition d) {
                out.add(new ProjectTool(e.getKey(), d.executable(), d.disabled() ? List.of() : List.copyOf(d.operations().keySet()), replaces,
                        d.disabled(), null, hash));
            } else {
                out.add(new ProjectTool(e.getKey(), null, List.of(), replaces, false, (String) e.getValue(), hash));
            }
        }
        return out;
    }

    /** Each project tool file's SHA-256, by tool name: what the user confirms (D-066). */
    public static Map<String, String> projectHashes(Path workspace) {
        Map<String, String> out = new TreeMap<>();
        for (Path f : files(projectFolder(workspace))) {
            String n = f.getFileName().toString();
            out.put(n.substring(0, n.length() - 5), hashOf(f));
        }
        return out;
    }

    /** The project tools that are new or changed since {@code confirmed}. */
    public static List<String> changed(Map<String, String> current, Map<String, String> confirmed) {
        List<String> out = new ArrayList<>();
        current.forEach((n, h) -> {
            if (!h.equals(confirmed.get(n))) {
                out.add(n);
            }
        });
        return out;
    }

    /** Each {@code *.json} file in a folder, by tool name: a definition or the error text. */
    private static Map<String, Object> read(Path dir, Source source, String shown) {
        Map<String, Object> out = new TreeMap<>();
        for (Path f : files(dir)) {
            String fn = f.getFileName().toString();
            String name = fn.substring(0, fn.length() - 5);
            String display = shown + fn;
            try {
                if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
                    throw new DefinitionException(display + ": must be a regular file, not a link");
                }
                if (Files.size(f) > MAX_FILE_BYTES) {
                    throw new DefinitionException(display + ": is larger than 64 KB");
                }
                if (source == Source.USER && (othersCanWrite(f) || othersCanWrite(dir))) {
                    throw new DefinitionException(display + ": others can write it or its folder; run chmod o-w on them");
                }
                byte[] bytes = Files.readAllBytes(f);
                out.put(name, ToolDefinition.parse(name, new String(bytes, StandardCharsets.UTF_8), source, display, sha256(bytes)));
            } catch (DefinitionException e) {
                out.put(name, e.getMessage());
            } catch (IOException e) {
                out.put(name, display + ": cannot read: " + e.getMessage());
            }
        }
        return out;
    }

    private static List<Path> files(Path dir) {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".json") && p.getFileName().toString().length() > 5).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static boolean othersCanWrite(Path p) {
        try {
            var perms = Files.getPosixFilePermissions(p, LinkOption.NOFOLLOW_LINKS);
            return perms.contains(PosixFilePermission.OTHERS_WRITE);
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }

    static String hashOf(Path f) {
        try {
            return sha256(Files.readAllBytes(f));
        } catch (IOException e) {
            return "unreadable";
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Whether the user's file for a shipped tool differs from the shipped default. */
    public static boolean differsFromDefault(Path home, String name) {
        if (!DEFAULTS.contains(name)) {
            return false;
        }
        Path f = userFolder(home).resolve(name + ".json");
        return Files.isRegularFile(f) && !hashOf(f).equals(sha256(defaultText(name).getBytes(StandardCharsets.UTF_8)));
    }

    public Map<String, ToolDefinition> tools() {
        return tools;
    }

    /** Files that failed to load, by tool name: those tools are unavailable, with this reason. */
    public Map<String, String> errors() {
        return errors;
    }

    /** The project's tools in use this session (empty when it isn't trusted or they weren't confirmed). */
    public List<ProjectTool> project() {
        return project;
    }

    public ToolDefinition get(String name) {
        return tools.get(name);
    }

    /** Builds a set from given definitions; for tests. */
    static ToolDefinitions of(Map<String, ToolDefinition> tools) {
        return new ToolDefinitions(new LinkedHashMap<>(tools), Map.of(), List.of());
    }
}
