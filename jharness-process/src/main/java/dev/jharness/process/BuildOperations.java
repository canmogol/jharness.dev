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
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.jharness.config.Timeouts;
import dev.jharness.events.Json;
import dev.jharness.process.ToolDefinition.Field;
import dev.jharness.process.ToolDefinition.Proxy;
import dev.jharness.process.ToolDefinition.Type;
import dev.jharness.security.Workspace;

/**
 * Typed operations of the defined tools (D-008, D-049, D-066, specs 013 and 028). Requests are JSON
 * objects such as {@code {"op":"test","tests":"AppTest"}}; free-form arguments are never accepted: each
 * field's checked value goes into the argv slot its definition names. Every run goes through the OS
 * sandbox with the tool's writable folders and the proxy port, and the tool is pointed at the egress
 * proxy without putting the password on the command line.
 */
public final class BuildOperations {

    static final String BASE_PATH = "/usr/bin:/bin:/usr/sbin:/sbin";

    private final ProcessRunner runner;
    private final Workspace workspace;
    private final Duration timeout;
    private final Duration max;
    private final boolean trusted;
    private final ToolDefinitions tools;
    private final Map<String, Executable> onPath;
    private final Path home;
    private final String javaHome;
    private final boolean offline;

    /**
     * @param trusted whether the project is trusted (D-011); tools run project code, so they need it
     * @param onPath each tool's executable found on {@code PATH} outside the workspace; missing tools are absent
     * @param home the user's home, for the writable folders
     * @param javaHome {@code JAVA_HOME} for tools whose definition sets {@code javaHome}, or null
     * @param offline add each definition's {@code offline} arguments and variables (D-033, D-055)
     */
    public BuildOperations(ProcessRunner runner, Workspace workspace, Timeouts timeouts, boolean trusted, ToolDefinitions tools,
            Map<String, Executable> onPath, Path home, String javaHome, boolean offline) {
        this.runner = runner;
        this.workspace = workspace;
        this.timeout = timeouts.process();
        this.max = timeouts.processMax();
        this.trusted = trusted;
        this.tools = tools;
        this.onPath = Map.copyOf(onPath);
        this.home = home;
        this.javaHome = javaHome != null && !javaHome.isBlank() && Files.isDirectory(Path.of(javaHome)) ? javaHome : null;
        this.offline = offline;
    }

    /** Finds each tool's executable on this process's {@code PATH}, skipping folders inside the workspace. */
    public static Map<String, Executable> findOnPath(ToolDefinitions tools, Path workspace) {
        Map<String, Executable> m = new LinkedHashMap<>();
        for (ToolDefinition d : tools.tools().values()) {
            Executables.resolve(d.executable(), workspace).ifPresent(e -> m.put(d.name(), e));
        }
        return m;
    }

    public ToolDefinitions tools() {
        return tools;
    }

    /** The definition, after checking the tool exists. */
    public ToolDefinition definition(String name) {
        ToolDefinition d = tools.get(name);
        if (d == null) {
            String err = tools.errors().get(name);
            if (err != null) {
                throw new OperationException("tool " + name + " can't be used: " + err);
            }
            throw new OperationException("no tool \"" + name + "\" (tools: " + (tools.tools().isEmpty() ? "none" : String.join(", ", tools.tools().keySet()))
                    + "; api.tools() lists them)");
        }
        return d;
    }

    /** The operation name, after checking it exists. */
    public static String op(ToolDefinition d, ObjectNode req) {
        JsonNode op = req.get("op");
        if (op == null || !op.isTextual() || !d.operations().containsKey(op.asText())) {
            throw new OperationException(d.name() + " op must be one of " + String.join(", ", new TreeSet<>(d.operations().keySet())));
        }
        return op.asText();
    }

    /**
     * Checks the request and the project without running anything, so a bad request fails before
     * anyone is asked to approve it.
     */
    public void check(String tool, ObjectNode req) {
        ToolDefinition d = definition(tool);
        if (!trusted) {
            throw new OperationException(tool + " runs the project's own code; trust this project first"
                    + " (JHarness asks when it opens a folder; see ~/.jharness/trusted.json)");
        }
        args(d, req, null);
        executable(d);
        CallTimeout.of(req, timeout, max);
    }

    /** The checked field values of a request, by field name (flags only when true). */
    private Map<String, JsonNode> values(ToolDefinition d, String op, ObjectNode req) {
        List<String> takes = d.fieldsOf(op);
        for (Iterator<String> it = req.fieldNames(); it.hasNext(); ) {
            String f = it.next();
            if (!f.equals("op") && !f.equals(CallTimeout.FIELD) && !takes.contains(f)) {
                List<String> list = new ArrayList<>(new TreeSet<>(takes));
                list.add(CallTimeout.FIELD);
                throw new OperationException(d.name() + " " + op + " does not take \"" + f + "\" (takes: " + String.join(", ", list) + ")");
            }
        }
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (String name : takes) {
            Field f = d.fields().get(name);
            JsonNode v = req.get(name);
            if (v == null || v.isNull()) {
                if (f.required()) {
                    throw new OperationException(d.name() + " " + op + " needs a \"" + name + "\"" + (f.description().isEmpty() ? "" : ": " + f.description()));
                }
                v = f.dflt();
                if (v == null) {
                    continue;
                }
            }
            String err = ToolDefinition.valueError(f, v);
            if (err != null) {
                throw new OperationException(err);
            }
            if (f.type() == Type.FLAG && !v.asBoolean()) {
                continue;
            }
            if (f.type() == Type.FOLDER) {
                v = Json.MAPPER.getNodeFactory().textNode(folder(name, v.asText()));
            }
            if ("npm-script".equals(f.check()) && !scripts().contains(v.asText())) {
                throw new OperationException("package.json has no script \"" + v.asText() + "\" (scripts: " + String.join(", ", new TreeSet<>(scripts())) + ")");
            }
            out.put(name, v);
        }
        for (List<String> group : d.conflicts()) {
            List<String> present = group.stream().filter(out::containsKey).toList();
            if (present.size() > 1) {
                throw new OperationException(String.join(" and ", present.stream().map(x -> "\"" + x + "\"").toList()) + " can't be used together");
            }
        }
        return out;
    }

    /** The argv after the executable, validated. Exposed for tests; {@code settings} is Maven's generated settings file. */
    List<String> args(ToolDefinition d, ObjectNode req, Path settings) {
        String op = op(d, req);
        Map<String, JsonNode> values = values(d, op, req);
        List<String> a = new ArrayList<>();
        for (String x : d.args()) {
            if (x.equals("{" + ToolDefinition.PROXY_SETTINGS + "}")) {
                if (settings != null) {
                    a.addAll(List.of("--global-settings", settings.toString()));
                }
            } else {
                a.add(x);
            }
        }
        if (offline) {
            a.addAll(d.offlineArgs());
        }
        for (String x : d.operations().get(op)) {
            Matcher m = ToolDefinition.PLACEHOLDER.matcher(x);
            if (m.matches()) {
                JsonNode v = values.get(m.group(1));
                if (v != null) {
                    a.addAll(expand(d.fields().get(m.group(1)), v));
                }
                continue;
            }
            StringBuilder sb = new StringBuilder();
            m.reset();
            while (m.find()) {
                JsonNode v = values.get(m.group(1));
                m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : expand(d.fields().get(m.group(1)), v).getFirst()));
            }
            m.appendTail(sb);
            if (!sb.isEmpty()) {
                a.add(sb.toString());
            }
        }
        return a;
    }

    List<String> args(String tool, ObjectNode req) {
        return args(definition(tool), req, null);
    }

    List<String> args(String tool, ObjectNode req, Path settings) {
        return args(definition(tool), req, settings);
    }

    private static List<String> expand(Field f, JsonNode v) {
        if (f.type() == Type.ENUM) {
            return f.values().get(v.asText());
        }
        String s = f.type() == Type.FLAG ? "" : v.asText();
        return f.args().stream().map(x -> x.replace("{}", s)).toList();
    }

    /**
     * The wrapper in the workspace when the definition names one and it exists (trusted projects only,
     * constitution Article IV), otherwise the tool from {@code PATH}.
     */
    Executable executable(ToolDefinition d) {
        if (d.wrapper() != null) {
            Optional<Executable> w = wrapper(d.wrapper().file());
            if (w.isPresent()) {
                if (d.wrapper().checksum() != null && !pinsChecksum(workspace.root().resolve(d.wrapper().checksum()))) {
                    throw new OperationException("./" + d.wrapper().file() + " is present but " + d.wrapper().checksum() + " doesn't pin"
                            + " distributionSha256Sum; add the checksum of the distribution so the wrapper can't fetch another");
                }
                return w.get();
            }
        }
        Executable e = onPath.get(d.name());
        if (e == null) {
            throw new OperationException(d.name() + " is not installed (" + d.executable() + " was not found on PATH outside the workspace)");
        }
        return e;
    }

    Executable executable(String tool) {
        return executable(definition(tool));
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

    /** The folders the tool may write besides the workspace and the temp folder (AC-08). */
    List<Path> caches(ToolDefinition d) {
        return d.writable().stream().map(home::resolve).toList();
    }

    List<Path> caches(String tool) {
        return caches(definition(tool));
    }

    /** The environment for the tool: {@code PATH}, {@code JAVA_HOME}, the proxy settings and the definition's variables. */
    Map<String, String> env(ToolDefinition d, Executable exe, ChildSandbox.ProxyRoute route, Path tmp) {
        Map<String, String> env = new LinkedHashMap<>();
        StringBuilder path = new StringBuilder();
        if (javaHome != null && d.javaHome()) {
            env.put("JAVA_HOME", javaHome);
            path.append(Path.of(javaHome, "bin")).append(':');
        }
        if (exe.searchDir() != null && !exe.searchDir().startsWith(workspace.root())) {
            path.append(exe.searchDir()).append(':');
        }
        path.append(BASE_PATH);
        env.put("PATH", path.toString());
        String proxyUrl = "http://" + route.user() + ":" + route.password() + "@127.0.0.1:" + route.port();
        switch (d.proxy()) {
            case MAVEN_SETTINGS, JAVA_OPTIONS -> env.put("JAVA_TOOL_OPTIONS", javaToolOptions(route, tmp));
            case NPM -> {
                env.put("npm_config_proxy", proxyUrl);
                env.put("npm_config_https_proxy", proxyUrl);
            }
            case ENV -> {
                for (String k : List.of("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy")) {
                    env.put(k, proxyUrl);
                }
                env.put("NO_PROXY", "localhost,127.0.0.1");
                env.put("no_proxy", "localhost,127.0.0.1");
            }
            case NONE -> {
                // No network for this tool.
            }
        }
        env.putAll(d.env());
        if (offline) {
            env.putAll(d.offlineEnv());
        }
        // A value that starts with ~/ is a folder in the home (spec 059), such as a cache the definition makes writable.
        env.replaceAll((k, v) -> d.env().containsKey(k) || d.offlineEnv().containsKey(k) ? homePath(v) : v);
        return env;
    }

    private String homePath(String v) {
        return v.startsWith("~/") ? home.resolve(v.substring(2)).toString() : v;
    }

    Map<String, String> env(String tool, Executable exe, ChildSandbox.ProxyRoute route, Path tmp) {
        return env(definition(tool), exe, route, tmp);
    }

    /** Proxy system properties for every JVM a build starts, and the private temp folder. */
    static String javaToolOptions(ChildSandbox.ProxyRoute route, Path tmp) {
        List<String> o = new ArrayList<>();
        o.add("-Djava.io.tmpdir=" + tmp);
        // A dual-stack JVM socket to 127.0.0.1 is ::ffff:127.0.0.1, which Seatbelt's "localhost" doesn't match.
        o.add("-Djava.net.preferIPv4Stack=true");
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

    public ProcessResult run(String tool, ObjectNode req) {
        check(tool, req);
        ToolDefinition d = definition(tool);
        ChildSandbox sandbox = runner.sandbox();
        ChildSandbox.ProxyRoute route = sandbox.route();
        if (route == null) {
            throw new OperationException(tool + " needs the egress proxy, and this session has none");
        }
        Executable exe = executable(d);
        Path settings = d.proxy() == Proxy.MAVEN_SETTINGS ? writeMavenSettings(route) : null;
        List<String> argv = args(d, req, settings);
        List<Path> caches = caches(d);
        for (Path c : caches) {
            try {
                Files.createDirectories(c);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot create " + c, e);
            }
        }
        Map<String, String> env = env(d, exe, route, sandbox.policy().tmp());
        ChildSandbox.Grants grants = grants(d, caches);
        // Seatbelt now and then refuses a connect to the proxy port with EPERM (spec 019). The tools
        // don't retry that themselves, so the whole approved run is repeated.
        return runner.retryingProxyRefusals(exe.name(), CallTimeout.of(req, timeout, max),
                r -> ProcessRunner.proxyRefused(r.full(), route.port()),
                t -> runner.run(exe, argv, workspace.root(), t, env, grants));
    }

    /** {@code loopback} (only in the user's own definitions, D-050) adds any loopback port; {@code proxy: none} gets no network. */
    static ChildSandbox.Grants grants(ToolDefinition d, List<Path> caches) {
        boolean network = d.proxy() != Proxy.NONE;
        return new ChildSandbox.Grants(caches, network, network && d.loopback());
    }

    /** Result as the JSON string {@code api.tool} returns. */
    public static String toJson(ProcessResult r) {
        return GitOperations.toJson(r);
    }

    /** How a refused write reads in a tool's output: Linux's read-only binds, macOS's Seatbelt, or a plain mode bit. */
    static final java.util.regex.Pattern WRITE_REFUSED = java.util.regex.Pattern.compile(
            "read-only file system|operation not permitted|permission denied", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * The result {@code api.tool} returns (spec 060): with a {@code hint} when the run failed and its output looks like a
     * refused write. The hint says where the tool may write, so the model tells the user instead of guessing.
     */
    public String toJson(String tool, ProcessResult r) {
        String json = GitOperations.toJson(r);
        String hint = writeHint(tool, r);
        if (hint == null) {
            return json;
        }
        ObjectNode o = Json.parseObject(json);
        o.put("hint", hint);
        return Json.write(o);
    }

    String writeHint(String tool, ProcessResult r) {
        if (r.exitCode() == 0 || !WRITE_REFUSED.matcher(r.output()).find()) {
            return null;
        }
        List<String> where = new ArrayList<>(List.of("the workspace (except .jharness/)", "a private temp folder ($TMPDIR)"));
        for (Path c : caches(definition(tool))) {
            where.add(c.startsWith(home) ? "~/" + home.relativize(c) : c.toString());
        }
        return "If this failed on a refused write: the OS sandbox lets " + tool + " write only to " + String.join(", ", where)
                + ". Other folders, such as a default cache elsewhere in the home, are refused, and no api call can change that. "
                + "Tell the user: the folders and variables come from the tool's definition (jharness tools show " + tool
                + "), and jharness tools reset " + tool + " writes the one this JHarness ships.";
    }

    /**
     * The detail for permission prompts and saved rules: the tool, where its definition came from and
     * the operation, keyed with the resolved executable so "always" doesn't carry over to another program.
     */
    public ObjectNode detail(String tool, ObjectNode req) {
        ToolDefinition d = definition(tool);
        String op = op(d, req);
        Executable exe = executable(d);
        return Json.object().put("tool", tool).put("source", d.source().wire()).put("executable", exe.path().toString())
                .put("op", tool + " " + op + " (" + exe.path() + ")");
    }

    /**
     * What the approval question shows (spec 023): the operation and the fields that change it, e.g.
     * {@code maven package · skipTests, profiles dev}; a project's tool says so.
     */
    public String summary(String tool, ObjectNode req) {
        ToolDefinition d = definition(tool);
        String op = op(d, req);
        StringBuilder b = new StringBuilder(tool).append(' ').append(op);
        List<String> parts = new ArrayList<>();
        for (String f : d.fieldsOf(op)) {
            JsonNode n = req.get(f);
            if (n == null || n.isNull() || n.isBoolean() && !n.asBoolean()) {
                continue;
            }
            parts.add(n.isBoolean() ? f : f + " " + n.asText());
        }
        if (!parts.isEmpty()) {
            b.append(" · ").append(String.join(", ", parts));
        }
        if (d.source() == ToolDefinition.Source.PROJECT) {
            b.append(" (the project's tool)");
        }
        return b.toString();
    }

    /** {@code api.tools()}: every tool with its operations and fields, and whether it can run here. */
    public String list() {
        ArrayNode out = Json.array();
        for (ToolDefinition d : tools.tools().values()) {
            ObjectNode o = d.describe();
            String reason = null;
            if (!trusted) {
                reason = "this project isn't trusted";
            } else if (onPath.get(d.name()) == null && (d.wrapper() == null || !Files.exists(workspace.root().resolve(d.wrapper().file())))) {
                reason = d.executable() + " was not found on PATH outside the workspace";
            }
            o.put("available", reason == null);
            if (reason != null) {
                o.put("reason", reason);
            }
            out.add(o);
        }
        tools.errors().forEach((n, e) -> out.add(Json.object().put("name", n).put("available", false).put("reason", e)));
        return Json.write(out);
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

    private String folder(String field, String value) {
        Path real = workspace.resolve(value);
        if (!Files.isDirectory(real)) {
            throw new OperationException("\"" + field + "\" must be a folder in the workspace: " + value);
        }
        String rel = workspace.relative(real);
        if (rel.isEmpty() || rel.equals(".")) {
            throw new OperationException("\"" + field + "\" must be a sub-folder; leave it out to build everything");
        }
        return rel.startsWith("-") ? "./" + rel : rel;
    }

    static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    @Override
    public String toString() {
        return "BuildOperations[" + workspace.root().getFileName() + ", trusted=" + trusted + ", tools=" + onPath.keySet() + "]";
    }
}
