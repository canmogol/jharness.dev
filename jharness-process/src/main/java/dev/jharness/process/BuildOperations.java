package dev.jharness.process;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.jharness.config.Timeouts;
import dev.jharness.events.Json;
import dev.jharness.security.Workspace;

/**
 * Typed Maven, Gradle and npm operations (D-008, D-009, D-049, spec 013). Requests are JSON objects
 * such as {@code {"op":"test","tests":"AppTest"}}; free-form arguments are never accepted. Every run
 * goes through the OS sandbox with the tool's cache folders and the proxy port, and the tool is
 * pointed at the egress proxy without putting the password on the command line.
 */
public final class BuildOperations {

    public enum Tool {
        MAVEN("maven", "mvn"), GRADLE("gradle", "gradle"), NPM("npm", "npm");

        private final String api;
        private final String executable;

        Tool(String api, String executable) {
            this.api = api;
            this.executable = executable;
        }

        /** The {@code api} method name: {@code maven}, {@code gradle} or {@code npm}. */
        public String api() {
            return api;
        }

        /** The executable looked up on {@code PATH}. */
        public String executable() {
            return executable;
        }
    }

    private static final Map<Tool, Map<String, Set<String>>> FIELDS = Map.of(
            Tool.MAVEN, Map.of(
                    "compile", Set.of("module"),
                    "test", Set.of("module", "tests"),
                    "verify", Set.of("module", "tests"),
                    "package", Set.of("module"),
                    "clean", Set.of("module")),
            Tool.GRADLE, Map.of(
                    "build", Set.of("project"),
                    "test", Set.of("project", "tests"),
                    "check", Set.of("project"),
                    "assemble", Set.of("project"),
                    "clean", Set.of("project")),
            Tool.NPM, Map.of(
                    "ci", Set.of(),
                    "install", Set.of(),
                    "test", Set.of(),
                    "run", Set.of("script")));

    /** Test selectors such as {@code AppTest}, {@code com.x.*Test#method} or {@code A,B}; never an option. */
    static final String TESTS = "[A-Za-z0-9_.*#$][A-Za-z0-9_.*#,+$-]{0,199}";
    /** Gradle project paths such as {@code :app} or {@code :libs:core}. */
    static final String GRADLE_PROJECT = "(:[A-Za-z0-9_][A-Za-z0-9_.-]{0,99}){1,10}";
    /** npm script names. */
    static final String SCRIPT = "[A-Za-z0-9_][A-Za-z0-9_.:@/-]{0,99}";

    static final String BASE_PATH = "/usr/bin:/bin:/usr/sbin:/sbin";

    /** Spec 013 open question: Gradle's client and daemon need loopback TCP, which the macOS sandbox can't limit to them. */
    static final String GRADLE_ON_MACOS = "gradle can't run in the macOS sandbox yet: Gradle's client and its daemon talk over"
            + " local TCP, and the sandbox blocks that so a build can't reach other services on this Mac. Run Gradle yourself"
            + " for now, or use Maven or npm; on Linux it works.";

    private final ProcessRunner runner;
    private final Workspace workspace;
    private final Duration timeout;
    private final Duration max;
    private final boolean trusted;
    private final Map<Tool, Executable> onPath;
    private final Path home;
    private final String javaHome;

    /**
     * @param trusted whether the project is trusted (D-011); builds run project code, so they need it
     * @param onPath the tools found on {@code PATH} outside the workspace; missing tools are absent
     * @param home the user's home, for the cache folders
     * @param javaHome {@code JAVA_HOME} to pass to Maven and Gradle, or null
     */
    public BuildOperations(ProcessRunner runner, Workspace workspace, Timeouts timeouts, boolean trusted,
            Map<Tool, Executable> onPath, Path home, String javaHome) {
        this.runner = runner;
        this.workspace = workspace;
        this.timeout = timeouts.process();
        this.max = timeouts.processMax();
        this.trusted = trusted;
        this.onPath = Map.copyOf(onPath);
        this.home = home;
        this.javaHome = javaHome != null && !javaHome.isBlank() && Files.isDirectory(Path.of(javaHome)) ? javaHome : null;
    }

    /** Finds each tool on this process's {@code PATH}, skipping folders inside the workspace. */
    public static Map<Tool, Executable> findOnPath(Path workspace) {
        Map<Tool, Executable> m = new LinkedHashMap<>();
        for (Tool t : Tool.values()) {
            Executables.resolve(t.executable(), workspace).ifPresent(e -> m.put(t, e));
        }
        return m;
    }

    public static Set<String> operations(Tool tool) {
        return FIELDS.get(tool).keySet();
    }

    /** The operation name, after checking it exists. */
    public static String op(Tool tool, ObjectNode req) {
        JsonNode op = req.get("op");
        if (op == null || !op.isTextual() || !FIELDS.get(tool).containsKey(op.asText())) {
            throw new OperationException(tool.api() + " op must be one of " + String.join(", ", new TreeSet<>(FIELDS.get(tool).keySet())));
        }
        return op.asText();
    }

    /**
     * Checks the request and the project without running anything, so a bad request fails before
     * anyone is asked to approve it.
     */
    public void check(Tool tool, ObjectNode req) {
        if (!trusted) {
            throw new OperationException(tool.api() + " runs the project's own build code; trust this project first"
                    + " (JHarness asks when it opens a folder; see ~/.jharness/trusted.json)");
        }
        if (tool == Tool.GRADLE && runner.sandbox().mechanism() == ChildSandbox.Mechanism.SEATBELT) {
            throw new OperationException(GRADLE_ON_MACOS);
        }
        args(tool, req);
        executable(tool);
        CallTimeout.of(req, timeout, max);
    }

    /** The argv after the executable, validated. Exposed for tests; {@code settings} is Maven's generated settings file. */
    List<String> args(Tool tool, ObjectNode req, Path settings) {
        String op = op(tool, req);
        Set<String> allowed = FIELDS.get(tool).get(op);
        for (Iterator<String> it = req.fieldNames(); it.hasNext(); ) {
            String f = it.next();
            if (!f.equals("op") && !f.equals(CallTimeout.FIELD) && !allowed.contains(f)) {
                List<String> takes = new ArrayList<>(new TreeSet<>(allowed));
                takes.add(CallTimeout.FIELD);
                throw new OperationException(tool.api() + " " + op + " does not take \"" + f + "\" (takes: " + String.join(", ", takes) + ")");
            }
        }
        List<String> a = new ArrayList<>();
        switch (tool) {
            case MAVEN -> {
                a.addAll(List.of("-B", "-ntp", "-Dstyle.color=never"));
                if (settings != null) {
                    a.addAll(List.of("--global-settings", settings.toString()));
                }
                String module = text(req, "module");
                if (module != null) {
                    a.addAll(List.of("-pl", folder(module), "-am"));
                }
                String tests = text(req, "tests");
                if (tests != null) {
                    a.add("-Dtest=" + matching(tests, TESTS, "tests"));
                    a.add("-Dsurefire.failIfNoSpecifiedTests=false");
                }
                a.add(op);
            }
            case GRADLE -> {
                a.addAll(List.of("--console=plain", "--no-daemon"));
                String project = text(req, "project");
                a.add((project != null ? matching(project, GRADLE_PROJECT, "project") + ":" : "") + op);
                String tests = text(req, "tests");
                if (tests != null) {
                    a.addAll(List.of("--tests", matching(tests, TESTS, "tests")));
                }
            }
            case NPM -> {
                switch (op) {
                    case "ci" -> a.addAll(List.of("ci", "--ignore-scripts"));
                    case "install" -> a.addAll(List.of("install", "--ignore-scripts"));
                    case "test" -> a.add("test");
                    case "run" -> {
                        String script = text(req, "script");
                        if (script == null) {
                            throw new OperationException("npm run needs a \"script\" from package.json");
                        }
                        matching(script, SCRIPT, "script");
                        if (!scripts().contains(script)) {
                            throw new OperationException("package.json has no script \"" + script + "\" (scripts: "
                                    + String.join(", ", new TreeSet<>(scripts())) + ")");
                        }
                        a.addAll(List.of("run", script));
                    }
                    default -> throw new AssertionError(op);
                }
            }
        }
        return a;
    }

    List<String> args(Tool tool, ObjectNode req) {
        return args(tool, req, null);
    }

    /**
     * The wrapper in the workspace when there is one (trusted projects only, constitution Article IV),
     * otherwise the tool from {@code PATH}.
     */
    Executable executable(Tool tool) {
        switch (tool) {
            case MAVEN -> {
                Optional<Executable> w = wrapper("mvnw");
                if (w.isPresent()) {
                    return w.get();
                }
            }
            case GRADLE -> {
                Optional<Executable> w = wrapper("gradlew");
                if (w.isPresent()) {
                    Path props = workspace.root().resolve("gradle/wrapper/gradle-wrapper.properties");
                    if (!pinsChecksum(props)) {
                        throw new OperationException("./gradlew is present but gradle/wrapper/gradle-wrapper.properties doesn't pin"
                                + " distributionSha256Sum; add the checksum of the Gradle distribution so the wrapper can't fetch another");
                    }
                    return w.get();
                }
            }
            case NPM -> {
                // npm has no wrapper.
            }
        }
        Executable e = onPath.get(tool);
        if (e == null) {
            throw new OperationException(tool.api() + " is not installed (" + tool.executable() + " was not found on PATH outside the workspace)");
        }
        return e;
    }

    private Optional<Executable> wrapper(String name) {
        Path p = workspace.root().resolve(name);
        if (!Files.exists(p)) {
            return Optional.empty();
        }
        Path real = workspace.resolve(name);
        if (!Files.isRegularFile(real) || !Files.isExecutable(real)) {
            throw new OperationException("./" + name + " is not an executable file");
        }
        return Optional.of(new Executable(name, real, workspace.root()));
    }

    static boolean pinsChecksum(Path props) {
        Properties p = new Properties();
        try (var in = Files.newInputStream(props)) {
            p.load(in);
        } catch (IOException e) {
            return false;
        }
        String sum = p.getProperty("distributionSha256Sum", "").strip();
        return sum.matches("[0-9a-fA-F]{64}");
    }

    /** The cache folders the tool may write besides the workspace and the temp folder (AC-08). */
    List<Path> caches(Tool tool) {
        return switch (tool) {
            case MAVEN -> List.of(home.resolve(".m2").resolve("repository"), home.resolve(".m2").resolve("wrapper"));
            case GRADLE -> {
                Path g = home.resolve(".gradle");
                yield List.of(g.resolve("caches"), g.resolve("wrapper"), g.resolve("native"), g.resolve("jdks"), g.resolve("daemon"),
                        g.resolve("notifications"), g.resolve(".tmp"));
            }
            case NPM -> List.of(home.resolve(".npm"));
        };
    }

    /** The environment for the tool: {@code PATH}, {@code JAVA_HOME} and the proxy settings (AC-06). */
    Map<String, String> env(Tool tool, Executable exe, ChildSandbox.ProxyRoute route, Path tmp) {
        Map<String, String> env = new LinkedHashMap<>();
        StringBuilder path = new StringBuilder();
        if (javaHome != null && tool != Tool.NPM) {
            env.put("JAVA_HOME", javaHome);
            path.append(Path.of(javaHome, "bin")).append(':');
        }
        if (exe.searchDir() != null && !exe.searchDir().startsWith(workspace.root())) {
            path.append(exe.searchDir()).append(':');
        }
        path.append(BASE_PATH);
        env.put("PATH", path.toString());
        String proxyUrl = "http://" + route.user() + ":" + route.password() + "@127.0.0.1:" + route.port();
        switch (tool) {
            case MAVEN, GRADLE -> env.put("JAVA_TOOL_OPTIONS", javaToolOptions(route, tmp));
            case NPM -> {
                env.put("npm_config_proxy", proxyUrl);
                env.put("npm_config_https_proxy", proxyUrl);
                env.put("npm_config_update_notifier", "false");
                env.put("npm_config_fund", "false");
                env.put("npm_config_audit", "false");
                env.put("npm_config_color", "false");
                env.put("npm_config_progress", "false");
            }
        }
        return env;
    }

    /** Proxy system properties for Gradle and every JVM a build starts, and the private temp folder. */
    static String javaToolOptions(ChildSandbox.ProxyRoute route, Path tmp) {
        List<String> o = new ArrayList<>();
        o.add("-Djava.io.tmpdir=" + tmp);
        for (String scheme : List.of("http", "https")) {
            o.add("-D" + scheme + ".proxyHost=127.0.0.1");
            o.add("-D" + scheme + ".proxyPort=" + route.port());
            o.add("-D" + scheme + ".proxyUser=" + route.user());
            o.add("-D" + scheme + ".proxyPassword=" + route.password());
        }
        // The JDK refuses Basic proxy credentials for HTTPS tunnels unless this is cleared.
        o.add("-Djdk.http.auth.tunneling.disabledSchemes=");
        o.add("-Djdk.http.auth.proxying.disabledSchemes=");
        return String.join(" ", o);
    }

    /** Maven's global settings with the proxy (D-025): written once per session, 0600, next to the proxy socket. */
    static String mavenSettings(ChildSandbox.ProxyRoute route) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!-- Written by JHarness for this session. -->\n"
                + "<settings xmlns=\"http://maven.apache.org/SETTINGS/1.0.0\">\n  <proxies>\n");
        for (String scheme : List.of("http", "https")) {
            sb.append("    <proxy><id>jharness-").append(scheme).append("</id><active>true</active><protocol>").append(scheme)
                    .append("</protocol><host>127.0.0.1</host><port>").append(route.port()).append("</port><username>")
                    .append(xml(route.user())).append("</username><password>").append(xml(route.password()))
                    .append("</password><nonProxyHosts>localhost|127.0.0.1</nonProxyHosts></proxy>\n");
        }
        return sb.append("  </proxies>\n</settings>\n").toString();
    }

    private Path writeMavenSettings(ChildSandbox.ProxyRoute route) {
        Path dir = route.socket().getParent();
        Path f = dir.resolve("maven-settings.xml");
        try {
            Files.createDirectories(dir);
            Path t = Files.createTempFile(dir, "maven-settings", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(t, mavenSettings(route), StandardCharsets.UTF_8);
            Files.move(t, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + f, e);
        }
        return f;
    }

    public ProcessResult run(Tool tool, ObjectNode req) {
        check(tool, req);
        ChildSandbox sandbox = runner.sandbox();
        ChildSandbox.ProxyRoute route = sandbox.route();
        if (route == null) {
            throw new OperationException(tool.api() + " needs the egress proxy, and this session has none");
        }
        Executable exe = executable(tool);
        Path settings = tool == Tool.MAVEN ? writeMavenSettings(route) : null;
        List<String> argv = args(tool, req, settings);
        List<Path> caches = caches(tool);
        for (Path c : caches) {
            try {
                Files.createDirectories(c);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot create " + c, e);
            }
        }
        return runner.run(exe, argv, workspace.root(), CallTimeout.of(req, timeout, max),
                env(tool, exe, route, sandbox.policy().tmp()), new ChildSandbox.Grants(caches, true));
    }

    /** Result as the JSON string {@code api.maven}, {@code api.gradle} and {@code api.npm} return. */
    public static String toJson(ProcessResult r) {
        return GitOperations.toJson(r);
    }

    /** The detail for permission prompts and saved rules: tool and op. */
    public static ObjectNode detail(Tool tool, ObjectNode req) {
        return Json.object().put("tool", tool.api()).put("op", tool.api() + " " + op(tool, req));
    }

    private Set<String> scripts() {
        Path pkg = workspace.root().resolve("package.json");
        try {
            JsonNode root = Json.parse(Files.readString(pkg, StandardCharsets.UTF_8));
            Set<String> s = new TreeSet<>();
            root.path("scripts").fieldNames().forEachRemaining(s::add);
            return s;
        } catch (IOException e) {
            throw new OperationException("no package.json in the workspace");
        } catch (IllegalArgumentException e) {
            throw new OperationException("package.json is not valid JSON: " + e.getMessage());
        }
    }

    private String folder(String module) {
        Path real = workspace.resolve(module);
        if (!Files.isDirectory(real)) {
            throw new OperationException("\"module\" must be a folder in the workspace: " + module);
        }
        String rel = workspace.relative(real);
        if (rel.isEmpty() || rel.equals(".")) {
            throw new OperationException("\"module\" must be a sub-folder; leave it out to build everything");
        }
        return rel.startsWith("-") ? "./" + rel : rel;
    }

    private static String matching(String v, String regex, String field) {
        if (!v.matches(regex)) {
            throw new OperationException("\"" + field + "\" has characters JHarness doesn't pass to build tools: " + v);
        }
        return v;
    }

    private static String text(ObjectNode req, String f) {
        JsonNode n = req.get(f);
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isTextual() || n.asText().isEmpty()) {
            throw new OperationException("\"" + f + "\" must be a non-empty string");
        }
        return n.asText();
    }

    static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    @Override
    public String toString() {
        return "BuildOperations[" + workspace.root().getFileName() + ", trusted=" + trusted + ", tools=" + onPath.keySet().stream()
                .map(t -> t.api().toLowerCase(Locale.ROOT)).toList() + "]";
    }
}
