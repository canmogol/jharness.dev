package dev.jharness.process;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.jharness.events.Json;

/**
 * One tool definition file, {@code NAME.json} (spec 028, D-066): the executable, how it reaches the
 * proxy, the folders it may write, and its operations as argv templates over typed fields. Nothing a
 * request carries reaches the command line except checked field values, each in a fixed place.
 */
public final class ToolDefinition {

    public enum Source {
        USER, PROJECT;

        public String wire() {
            return name().toLowerCase();
        }
    }

    /** How the tool reaches the egress proxy (built-in methods; a definition only picks one). */
    public enum Proxy {
        /** A generated Maven settings file ({@code {proxySettings}}) and {@code JAVA_TOOL_OPTIONS}. */
        MAVEN_SETTINGS("maven-settings"),
        /** {@code JAVA_TOOL_OPTIONS} with the proxy system properties. */
        JAVA_OPTIONS("java-options"),
        /** {@code npm_config_proxy} and {@code npm_config_https_proxy}. */
        NPM("npm"),
        /** {@code HTTP_PROXY}, {@code HTTPS_PROXY} and their lower-case forms, with {@code NO_PROXY} for loopback. */
        ENV("env"),
        /** No network at all: the OS sandbox gives the tool none. */
        NONE("none");

        private final String wire;

        Proxy(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        static Proxy of(String s) {
            for (Proxy p : values()) {
                if (p.wire.equals(s)) {
                    return p;
                }
            }
            return null;
        }
    }

    public enum Type {
        FLAG, STRING, ENUM, INTEGER, FOLDER;

        public String wire() {
            return name().toLowerCase();
        }
    }

    /**
     * One typed field.
     *
     * @param args argv pieces, {@code {}} replaced by the value; for an enum, see {@code values}
     * @param values for an enum, each allowed value and the arguments it adds
     */
    public record Field(String name, Type type, Pattern pattern, Map<String, List<String>> values, Long min, Long max, boolean required,
            JsonNode dflt, String check, List<String> args, String description) {}

    public record Wrapper(String file, String checksum) {}

    /** Executables a definition may never name: shells and programs that run another command (Article IV). */
    public static final Set<String> REFUSED = Set.of("sh", "bash", "dash", "zsh", "ksh", "mksh", "csh", "tcsh", "fish", "ash", "env",
            "sudo", "su", "doas", "xargs", "nohup", "nice", "timeout", "busybox", "script", "setsid", "chroot", "exec", "eval",
            "command", "stdbuf", "unbuffer", "watch", "strace", "ltrace", "gdb");

    /** Variables a definition may not set: the loader, the shell, the paths and the proxy wiring belong to JHarness. */
    static final Pattern ENV_DENIED = Pattern.compile("(?i)LD_.*|DYLD_.*|PATH|HOME|TMPDIR|TMP|TEMP|LANG|LC_.*|IFS|ENV|BASH_ENV|"
            + "BASH_FUNC_.*|SHELL|PS4|PROMPT_COMMAND|JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS|JAVA_HOME|"
            + "(HTTP|HTTPS|FTP|ALL|NO)_PROXY|NPM_CONFIG_(HTTPS_)?PROXY|NPM_CONFIG_NOPROXY|GIT_.*|SSH_.*|GNUPGHOME|NODE_OPTIONS|"
            + "PYTHONSTARTUP|PYTHONPATH|PERL5OPT|PERL5LIB|RUBYOPT|RUBYLIB|XDG_.*");
    static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    static final Pattern TOOL_NAME = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    static final Pattern EXECUTABLE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}");
    static final Pattern OP_NAME = Pattern.compile("[a-z][A-Za-z0-9:_-]{0,63}");
    static final Pattern FIELD_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
    static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)\\}");
    /** Folders under the home a definition may never make writable. */
    static final Set<String> SENSITIVE = Set.of(".jharness", ".ssh", ".gnupg", ".aws", ".config", ".local", ".kube", ".docker", "Library",
            ".bashrc", ".zshrc", ".profile", ".bash_profile");
    static final String PROXY_SETTINGS = "proxySettings";
    static final int MAX_PATTERN = 500;
    static final int MAX_ARG = 1000;

    private static final Set<String> KEYS = Set.of("description", "executable", "wrapper", "proxy", "javaHome", "env", "writable",
            "loopback", "args", "offline", "operations", "fields", "conflicts", "disabled");
    private static final Set<String> FIELD_KEYS = Set.of("type", "pattern", "values", "min", "max", "required", "default", "check", "args",
            "description");
    private static final Set<String> RESERVED_FIELDS = Set.of("op", "timeoutMs", PROXY_SETTINGS);

    private final String name;
    private final Source source;
    private final String file;
    private final String hash;
    private final boolean disabled;
    private final String description;
    private final String executable;
    private final Wrapper wrapper;
    private final Proxy proxy;
    private final boolean javaHome;
    private final Map<String, String> env;
    private final List<String> writable;
    private final boolean loopback;
    private final List<String> args;
    private final List<String> offlineArgs;
    private final Map<String, String> offlineEnv;
    private final Map<String, List<String>> operations;
    private final Map<String, Field> fields;
    private final List<List<String>> conflicts;

    private ToolDefinition(Builder b) {
        this.name = b.name;
        this.source = b.source;
        this.file = b.file;
        this.hash = b.hash;
        this.disabled = b.disabled;
        this.description = b.description;
        this.executable = b.executable;
        this.wrapper = b.wrapper;
        this.proxy = b.proxy;
        this.javaHome = b.javaHome;
        this.env = Collections.unmodifiableMap(b.env);
        this.writable = List.copyOf(b.writable);
        this.loopback = b.loopback;
        this.args = List.copyOf(b.args);
        this.offlineArgs = List.copyOf(b.offlineArgs);
        this.offlineEnv = Collections.unmodifiableMap(b.offlineEnv);
        this.operations = Collections.unmodifiableMap(b.operations);
        this.fields = Collections.unmodifiableMap(b.fields);
        this.conflicts = List.copyOf(b.conflicts);
    }

    private static final class Builder {
        String name;
        Source source;
        String file;
        String hash;
        boolean disabled;
        String description = "";
        String executable;
        Wrapper wrapper;
        Proxy proxy;
        boolean javaHome;
        Map<String, String> env = new LinkedHashMap<>();
        List<String> writable = new ArrayList<>();
        boolean loopback;
        List<String> args = new ArrayList<>();
        List<String> offlineArgs = new ArrayList<>();
        Map<String, String> offlineEnv = new LinkedHashMap<>();
        Map<String, List<String>> operations = new LinkedHashMap<>();
        Map<String, Field> fields = new LinkedHashMap<>();
        List<List<String>> conflicts = new ArrayList<>();
    }

    /** A problem with a definition file; the message names the file and the key. */
    public static final class DefinitionException extends Exception {
        DefinitionException(String message) {
            super(message);
        }
    }

    /**
     * Parses and checks one definition.
     *
     * @param file shown in messages
     * @param hash the file's SHA-256, recorded when the user confirms a project's tools
     */
    public static ToolDefinition parse(String name, String text, Source source, String file, String hash) throws DefinitionException {
        String where = file;
        if (!TOOL_NAME.matcher(name).matches()) {
            throw new DefinitionException(where + ": tool names use lower-case letters, digits and '-' (at most 32), such as go or maven");
        }
        JsonNode root;
        try {
            root = Json.parse(text);
        } catch (IllegalArgumentException e) {
            throw new DefinitionException(where + ": not valid JSON: " + e.getMessage());
        }
        if (!root.isObject()) {
            throw new DefinitionException(where + ": must be a JSON object");
        }
        Builder b = new Builder();
        b.name = name;
        b.source = source;
        b.file = file;
        b.hash = hash;
        for (var it = root.fieldNames(); it.hasNext(); ) {
            String k = it.next();
            if (!KEYS.contains(k)) {
                throw new DefinitionException(where + ": unknown key \"" + k + "\" (known: " + String.join(", ", new TreeSet<>(KEYS)) + ")");
            }
        }
        b.description = optText(root, "description", where, "");
        if (root.has("disabled")) {
            if (!root.get("disabled").isBoolean()) {
                throw new DefinitionException(where + ": \"disabled\" must be true or false");
            }
            if (root.get("disabled").asBoolean()) {
                for (var it = root.fieldNames(); it.hasNext(); ) {
                    String k = it.next();
                    if (!k.equals("disabled") && !k.equals("description")) {
                        throw new DefinitionException(where + ": a disabled tool takes only \"disabled\" and \"description\"");
                    }
                }
                b.disabled = true;
                return new ToolDefinition(b);
            }
        }
        b.executable = text(root, "executable", where);
        if (!EXECUTABLE.matcher(b.executable).matches()) {
            throw new DefinitionException(where + ": \"executable\" must be a program name looked up on PATH, such as go (no path)");
        }
        if (REFUSED.contains(b.executable)) {
            throw new DefinitionException(where + ": \"executable\" can't be " + b.executable + ": shells and command runners are never run (constitution Article IV)");
        }
        JsonNode w = root.get("wrapper");
        if (w != null) {
            userOnly(source, "wrapper", where);
            if (!w.isObject()) {
                throw new DefinitionException(where + ": \"wrapper\" must be an object with \"file\"");
            }
            for (var it = w.fieldNames(); it.hasNext(); ) {
                String k = it.next();
                if (!k.equals("file") && !k.equals("checksum")) {
                    throw new DefinitionException(where + ": wrapper." + k + " is not a known key (known: checksum, file)");
                }
            }
            String f = text(w, "file", where + ": wrapper");
            if (!EXECUTABLE.matcher(f).matches()) {
                throw new DefinitionException(where + ": wrapper.file must be a file name in the project root, such as mvnw");
            }
            String c = w.has("checksum") ? text(w, "checksum", where + ": wrapper") : null;
            if (c != null && (c.startsWith("/") || c.contains("..") || c.contains("\\"))) {
                throw new DefinitionException(where + ": wrapper.checksum must be a path inside the project");
            }
            b.wrapper = new Wrapper(f, c);
        }
        String proxy = text(root, "proxy", where);
        b.proxy = Proxy.of(proxy);
        if (b.proxy == null) {
            throw new DefinitionException(where + ": \"proxy\" must be one of maven-settings, java-options, npm, env or none");
        }
        if (root.has("javaHome")) {
            if (!root.get("javaHome").isBoolean()) {
                throw new DefinitionException(where + ": \"javaHome\" must be true or false");
            }
            b.javaHome = root.get("javaHome").asBoolean();
        }
        b.env.putAll(env(root.get("env"), where + ": env"));
        JsonNode wr = root.get("writable");
        if (wr != null) {
            userOnly(source, "writable", where);
            if (!wr.isArray()) {
                throw new DefinitionException(where + ": \"writable\" must be a list of folders such as ~/.m2/repository");
            }
            for (JsonNode n : wr) {
                b.writable.add(writable(n, where));
            }
        }
        JsonNode lb = root.get("loopback");
        if (lb != null) {
            userOnly(source, "loopback", where);
            if (!lb.isTextual() || !lb.asText().equals("macos")) {
                throw new DefinitionException(where + ": \"loopback\" can only be \"macos\" (D-050)");
            }
            if (b.proxy == Proxy.NONE) {
                throw new DefinitionException(where + ": \"loopback\" needs network, so \"proxy\" can't be none");
            }
            b.loopback = true;
        }
        b.args.addAll(strings(root.get("args"), where + ": args"));
        for (String a : b.args) {
            Matcher m = PLACEHOLDER.matcher(a);
            while (m.find()) {
                if (!m.group(1).equals(PROXY_SETTINGS) || !a.equals("{" + PROXY_SETTINGS + "}")) {
                    throw new DefinitionException(where + ": args can only hold fixed arguments and {proxySettings}, not " + a);
                }
                if (b.proxy != Proxy.MAVEN_SETTINGS) {
                    throw new DefinitionException(where + ": {proxySettings} needs \"proxy\": \"maven-settings\"");
                }
            }
        }
        JsonNode off = root.get("offline");
        if (off != null) {
            if (!off.isObject()) {
                throw new DefinitionException(where + ": \"offline\" must be an object with \"args\" or \"env\"");
            }
            for (var it = off.fieldNames(); it.hasNext(); ) {
                String k = it.next();
                if (!k.equals("args") && !k.equals("env")) {
                    throw new DefinitionException(where + ": offline." + k + " is not a known key (known: args, env)");
                }
            }
            b.offlineArgs.addAll(strings(off.get("args"), where + ": offline.args"));
            for (String a : b.offlineArgs) {
                if (PLACEHOLDER.matcher(a).find()) {
                    throw new DefinitionException(where + ": offline.args can only hold fixed arguments, not " + a);
                }
            }
            b.offlineEnv.putAll(env(off.get("env"), where + ": offline.env"));
        }
        JsonNode fs = root.get("fields");
        if (fs != null) {
            if (!fs.isObject()) {
                throw new DefinitionException(where + ": \"fields\" must be an object");
            }
            for (var it = fs.properties().iterator(); it.hasNext(); ) {
                var e = it.next();
                b.fields.put(e.getKey(), field(e.getKey(), e.getValue(), where + ": fields." + e.getKey()));
            }
        }
        JsonNode ops = root.get("operations");
        if (ops == null || !ops.isObject() || ops.isEmpty()) {
            throw new DefinitionException(where + ": \"operations\" must name at least one operation");
        }
        Set<String> used = new LinkedHashSet<>();
        for (var it = ops.properties().iterator(); it.hasNext(); ) {
            var e = it.next();
            String op = e.getKey();
            String at = where + ": operations." + op;
            if (!OP_NAME.matcher(op).matches()) {
                throw new DefinitionException(at + ": operation names use letters, digits, ':', '_' and '-', starting with a lower-case letter");
            }
            if (!e.getValue().isObject()) {
                throw new DefinitionException(at + " must be an object with \"args\"");
            }
            for (var k = e.getValue().fieldNames(); k.hasNext(); ) {
                String key = k.next();
                if (!key.equals("args")) {
                    throw new DefinitionException(at + "." + key + " is not a known key (known: args)");
                }
            }
            List<String> template = strings(e.getValue().get("args"), at + ".args");
            if (template.isEmpty()) {
                throw new DefinitionException(at + ".args must hold at least one argument");
            }
            Set<String> inOp = new LinkedHashSet<>();
            for (String a : template) {
                if (a.length() > MAX_ARG) {
                    throw new DefinitionException(at + ": an argument is longer than " + MAX_ARG + " characters");
                }
                Matcher m = PLACEHOLDER.matcher(a);
                boolean whole = false;
                while (m.find()) {
                    String f = m.group(1);
                    Field fd = b.fields.get(f);
                    if (fd == null) {
                        throw new DefinitionException(at + ": {" + f + "} names no field (fields: " + String.join(", ", b.fields.keySet()) + ")");
                    }
                    if (!inOp.add(f)) {
                        throw new DefinitionException(at + ": {" + f + "} appears twice");
                    }
                    whole = a.equals("{" + f + "}");
                    if (!whole && (fd.type() == Type.ENUM ? fd.values().values().stream().anyMatch(v -> v.size() != 1) : fd.args().size() != 1)) {
                        throw new DefinitionException(at + ": {" + f + "} is inside \"" + a + "\", so the field's args must be exactly one argument");
                    }
                }
            }
            used.addAll(inOp);
            b.operations.put(op, List.copyOf(template));
        }
        for (String f : b.fields.keySet()) {
            if (!used.contains(f)) {
                throw new DefinitionException(where + ": field \"" + f + "\" is used by no operation");
            }
        }
        JsonNode cs = root.get("conflicts");
        if (cs != null) {
            if (!cs.isArray()) {
                throw new DefinitionException(where + ": \"conflicts\" must be a list of field lists, such as [[\"tests\", \"skipTests\"]]");
            }
            for (JsonNode c : cs) {
                List<String> group = strings(c, where + ": conflicts");
                if (group.size() < 2) {
                    throw new DefinitionException(where + ": each conflicts entry names at least two fields");
                }
                for (String f : group) {
                    if (!b.fields.containsKey(f)) {
                        throw new DefinitionException(where + ": conflicts names \"" + f + "\", which is not a field");
                    }
                }
                b.conflicts.add(group);
            }
        }
        return new ToolDefinition(b);
    }

    private static void userOnly(Source source, String key, String where) throws DefinitionException {
        if (source == Source.PROJECT) {
            throw new DefinitionException(where + ": a project's tool can't set \"" + key + "\"; only your own ~/.jharness/tools/ files can (D-066)");
        }
    }

    private static Field field(String name, JsonNode n, String where) throws DefinitionException {
        if (!FIELD_NAME.matcher(name).matches() || RESERVED_FIELDS.contains(name)) {
            throw new DefinitionException(where + ": field names use letters, digits and '_', and can't be op, timeoutMs or proxySettings");
        }
        if (!n.isObject()) {
            throw new DefinitionException(where + " must be an object with \"type\"");
        }
        for (var it = n.fieldNames(); it.hasNext(); ) {
            String k = it.next();
            if (!FIELD_KEYS.contains(k)) {
                throw new DefinitionException(where + "." + k + " is not a known key (known: " + String.join(", ", new TreeSet<>(FIELD_KEYS)) + ")");
            }
        }
        String t = text(n, "type", where);
        Type type;
        try {
            type = Type.valueOf(t.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new DefinitionException(where + ".type must be flag, string, enum, integer or folder");
        }
        Pattern pattern = null;
        Map<String, List<String>> values = Map.of();
        Long min = null;
        Long max = null;
        if (n.has("pattern") != (type == Type.STRING)) {
            throw new DefinitionException(where + (type == Type.STRING ? ": a string field needs a \"pattern\"" : ": only string fields take \"pattern\""));
        }
        if (type == Type.STRING) {
            String p = text(n, "pattern", where);
            if (p.length() > MAX_PATTERN) {
                throw new DefinitionException(where + ".pattern is longer than " + MAX_PATTERN + " characters");
            }
            try {
                pattern = Pattern.compile(p);
            } catch (PatternSyntaxException e) {
                throw new DefinitionException(where + ".pattern is not a valid regular expression: " + e.getDescription());
            }
        }
        if (n.has("values") != (type == Type.ENUM)) {
            throw new DefinitionException(where + (type == Type.ENUM ? ": an enum field needs \"values\"" : ": only enum fields take \"values\""));
        }
        if (type == Type.ENUM) {
            JsonNode v = n.get("values");
            if (!v.isObject() || v.isEmpty()) {
                throw new DefinitionException(where + ".values must map each allowed value to its arguments, such as {\"fast\": [\"-O1\"]}");
            }
            Map<String, List<String>> m = new LinkedHashMap<>();
            for (var it = v.properties().iterator(); it.hasNext(); ) {
                var e = it.next();
                if (!FIELD_NAME.matcher(e.getKey()).matches() && !e.getKey().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")) {
                    throw new DefinitionException(where + ".values: \"" + e.getKey() + "\" is not a plain value");
                }
                List<String> a = strings(e.getValue(), where + ".values." + e.getKey());
                for (String x : a) {
                    if (PLACEHOLDER.matcher(x).find() || x.contains("{}")) {
                        throw new DefinitionException(where + ".values." + e.getKey() + ": enum arguments are fixed, without {}");
                    }
                }
                m.put(e.getKey(), a);
            }
            values = Collections.unmodifiableMap(m);
        }
        if ((n.has("min") || n.has("max")) != (type == Type.INTEGER) || type == Type.INTEGER && !(n.has("min") && n.has("max"))) {
            throw new DefinitionException(where + (type == Type.INTEGER ? ": an integer field needs \"min\" and \"max\"" : ": only integer fields take \"min\" and \"max\""));
        }
        if (type == Type.INTEGER) {
            if (!n.get("min").isIntegralNumber() || !n.get("max").isIntegralNumber() || n.get("min").asLong() > n.get("max").asLong()) {
                throw new DefinitionException(where + ": \"min\" and \"max\" must be whole numbers with min <= max");
            }
            min = n.get("min").asLong();
            max = n.get("max").asLong();
        }
        List<String> args;
        if (type == Type.ENUM) {
            if (n.has("args")) {
                throw new DefinitionException(where + ": an enum field's arguments go in \"values\"");
            }
            args = List.of();
        } else if (n.has("args")) {
            args = strings(n.get("args"), where + ".args");
        } else {
            args = type == Type.FLAG ? List.of() : List.of("{}");
        }
        if (type == Type.FLAG && args.isEmpty()) {
            throw new DefinitionException(where + ": a flag field needs \"args\"");
        }
        for (String a : args) {
            if (type == Type.FLAG && a.contains("{}")) {
                throw new DefinitionException(where + ": a flag has no value, so its args can't hold {}");
            }
            if (type != Type.FLAG && type != Type.ENUM && args.stream().noneMatch(x -> x.contains("{}"))) {
                throw new DefinitionException(where + ".args must use the value as {}");
            }
            if (PLACEHOLDER.matcher(a).find()) {
                throw new DefinitionException(where + ".args can't name other fields: " + a);
            }
        }
        boolean required = false;
        if (n.has("required")) {
            if (!n.get("required").isBoolean()) {
                throw new DefinitionException(where + ".required must be true or false");
            }
            required = n.get("required").asBoolean();
        }
        String check = null;
        if (n.has("check")) {
            check = text(n, "check", where);
            if (!check.equals("npm-script") || type != Type.STRING) {
                throw new DefinitionException(where + ".check: the only check is \"npm-script\", on a string field");
            }
        }
        String desc = optText(n, "description", where, "");
        Field f = new Field(name, type, pattern, values, min, max, required, null, check, List.copyOf(args), desc);
        if (n.has("default")) {
            if (required) {
                throw new DefinitionException(where + ": a required field has no default");
            }
            JsonNode d = n.get("default");
            String err = valueError(f, d);
            if (err != null) {
                throw new DefinitionException(where + ".default: " + err);
            }
            f = new Field(name, type, pattern, values, min, max, false, d, check, List.copyOf(args), desc);
        }
        return f;
    }

    /** Why {@code v} is not a valid value for {@code f}, or null; folders are checked against the workspace later. */
    static String valueError(Field f, JsonNode v) {
        switch (f.type()) {
            case FLAG -> {
                return v.isBoolean() ? null : "\"" + f.name() + "\" must be true or false";
            }
            case INTEGER -> {
                if (!v.isIntegralNumber() || v.asLong() < f.min() || v.asLong() > f.max()) {
                    return "\"" + f.name() + "\" must be a whole number from " + f.min() + " to " + f.max();
                }
                return null;
            }
            default -> {
                if (!v.isTextual() || v.asText().isEmpty()) {
                    return "\"" + f.name() + "\" must be a non-empty string";
                }
                String s = v.asText();
                if (s.startsWith("-")) {
                    return "\"" + f.name() + "\" can't start with '-': " + s;
                }
                if (s.length() > MAX_ARG) {
                    return "\"" + f.name() + "\" is longer than " + MAX_ARG + " characters";
                }
                if (f.type() == Type.ENUM && !f.values().containsKey(s)) {
                    return "\"" + f.name() + "\" must be one of " + String.join(", ", f.values().keySet());
                }
                if (f.type() == Type.STRING && !f.pattern().matcher(s).matches()) {
                    return "\"" + f.name() + "\" has characters JHarness doesn't pass to tools: " + s;
                }
                return null;
            }
        }
    }

    private static String writable(JsonNode n, String where) throws DefinitionException {
        if (!n.isTextual() || !n.asText().startsWith("~/")) {
            throw new DefinitionException(where + ": writable entries are folders in your home, written as ~/…, such as ~/.m2/repository");
        }
        String rel = n.asText().substring(2);
        if (rel.isEmpty() || rel.contains("..") || rel.startsWith("/") || rel.contains("//") || rel.contains("\\")) {
            throw new DefinitionException(where + ": writable entry " + n.asText() + " must be a folder below your home");
        }
        String first = rel.split("/")[0];
        if (SENSITIVE.contains(first)) {
            throw new DefinitionException(where + ": " + n.asText() + " can't be made writable (keys, settings and JHarness's own files live there)");
        }
        return rel.endsWith("/") ? rel.substring(0, rel.length() - 1) : rel;
    }

    private static Map<String, String> env(JsonNode n, String where) throws DefinitionException {
        Map<String, String> out = new LinkedHashMap<>();
        if (n == null) {
            return out;
        }
        if (!n.isObject()) {
            throw new DefinitionException(where + " must map variable names to strings");
        }
        for (var it = n.properties().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (!envNameAllowed(e.getKey())) {
                throw new DefinitionException(where + ": " + e.getKey() + " can't be set by a tool definition (JHarness sets the paths, the loader and the proxy)");
            }
            if (!e.getValue().isTextual() || e.getValue().asText().indexOf('\0') >= 0 || e.getValue().asText().length() > 4096) {
                throw new DefinitionException(where + "." + e.getKey() + " must be a string of at most 4096 characters");
            }
            String v = e.getValue().asText();
            if (v.startsWith("~/") && (v.length() == 2 || v.contains("..") || v.contains("//"))) {
                throw new DefinitionException(where + "." + e.getKey() + ": " + v + " must be a folder below your home");
            }
            out.put(e.getKey(), e.getValue().asText());
        }
        return out;
    }

    /** A variable a definition may set (and {@link ProcessRunner} may pass on). */
    static boolean envNameAllowed(String name) {
        return ENV_NAME.matcher(name).matches() && !ENV_DENIED.matcher(name).matches();
    }

    private static List<String> strings(JsonNode n, String where) throws DefinitionException {
        if (n == null) {
            return List.of();
        }
        if (!n.isArray()) {
            throw new DefinitionException(where + " must be a list of strings");
        }
        List<String> out = new ArrayList<>();
        for (JsonNode x : n) {
            if (!x.isTextual() || x.asText().isEmpty() || x.asText().indexOf('\0') >= 0) {
                throw new DefinitionException(where + " must be a list of non-empty strings");
            }
            out.add(x.asText());
        }
        return out;
    }

    private static String text(JsonNode n, String key, String where) throws DefinitionException {
        JsonNode v = n.get(key);
        if (v == null || !v.isTextual() || v.asText().isBlank()) {
            throw new DefinitionException(where + ": \"" + key + "\" is required and must be a non-empty string");
        }
        return v.asText();
    }

    private static String optText(JsonNode n, String key, String where, String dflt) throws DefinitionException {
        JsonNode v = n.get(key);
        if (v == null) {
            return dflt;
        }
        if (!v.isTextual()) {
            throw new DefinitionException(where + ": \"" + key + "\" must be a string");
        }
        return v.asText();
    }

    /** The fields an operation takes, in the order its template names them. */
    public List<String> fieldsOf(String op) {
        List<String> out = new ArrayList<>();
        for (String a : operations.get(op)) {
            Matcher m = PLACEHOLDER.matcher(a);
            while (m.find()) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    /** For {@code api.tools()} and {@code jharness tools}: the definition as the agent sees it. */
    public ObjectNode describe() {
        ObjectNode o = Json.object().put("name", name).put("description", description).put("source", source.wire());
        ObjectNode ops = o.putObject("operations");
        for (String op : operations.keySet()) {
            var a = ops.putArray(op);
            fieldsOf(op).forEach(a::add);
        }
        ObjectNode fs = o.putObject("fields");
        for (Field f : fields.values()) {
            ObjectNode x = fs.putObject(f.name()).put("type", f.type().wire());
            if (f.pattern() != null) {
                x.put("pattern", f.pattern().pattern());
            }
            if (f.type() == Type.ENUM) {
                var v = x.putArray("values");
                f.values().keySet().forEach(v::add);
            }
            if (f.min() != null) {
                x.put("min", f.min()).put("max", f.max());
            }
            if (f.required()) {
                x.put("required", true);
            }
            if (f.dflt() != null) {
                x.set("default", f.dflt());
            }
            if (!f.description().isEmpty()) {
                x.put("description", f.description());
            }
        }
        if (!conflicts.isEmpty()) {
            var c = o.putArray("conflicts");
            for (List<String> g : conflicts) {
                var a = c.addArray();
                g.forEach(a::add);
            }
        }
        return o;
    }

    public String name() {
        return name;
    }

    public Source source() {
        return source;
    }

    public String file() {
        return file;
    }

    public String hash() {
        return hash;
    }

    public boolean disabled() {
        return disabled;
    }

    public String description() {
        return description;
    }

    public String executable() {
        return executable;
    }

    public Wrapper wrapper() {
        return wrapper;
    }

    public Proxy proxy() {
        return proxy;
    }

    public boolean javaHome() {
        return javaHome;
    }

    public Map<String, String> env() {
        return env;
    }

    /** Writable folders relative to the home. */
    public List<String> writable() {
        return writable;
    }

    public boolean loopback() {
        return loopback;
    }

    public List<String> args() {
        return args;
    }

    public List<String> offlineArgs() {
        return offlineArgs;
    }

    public Map<String, String> offlineEnv() {
        return offlineEnv;
    }

    public Map<String, List<String>> operations() {
        return operations;
    }

    public Map<String, Field> fields() {
        return fields;
    }

    public List<List<String>> conflicts() {
        return conflicts;
    }
}
