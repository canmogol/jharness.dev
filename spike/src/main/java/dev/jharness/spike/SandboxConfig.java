package dev.jharness.spike;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Engine and sandbox limits (plan "Configurable options", AC-13). Resolved in order, later wins:
 * built-in {@code sandbox-defaults.json}, then a {@code --config} file, then {@code --set} overrides.
 *
 * <p>Only the keys in {@link #KEYS} are accepted. The policy, host access, isolate mode and thread
 * count are fixed and rejected if named. A limit can be changed but never removed: zero, negative
 * and unit-less values are rejected.
 */
final class SandboxConfig {

    static final class ConfigException extends Exception {
        ConfigException(String message) {
            super(message);
        }
    }

    enum Kind { SIZE, DURATION, COUNT, AUTO_BOOLEAN }

    static final Map<String, Kind> KEYS = new LinkedHashMap<>();

    static {
        KEYS.put("engine.MaxIsolateMemory", Kind.SIZE);
        KEYS.put("engine.MemoryProtection", Kind.AUTO_BOOLEAN);
        KEYS.put("sandbox.MaxHeapMemory", Kind.SIZE);
        KEYS.put("sandbox.MaxCPUTime", Kind.DURATION);
        KEYS.put("sandbox.MaxASTDepth", Kind.COUNT);
        KEYS.put("sandbox.MaxOutputStreamSize", Kind.SIZE);
        KEYS.put("sandbox.MaxErrorStreamSize", Kind.SIZE);
        KEYS.put("wallClock", Kind.DURATION);
    }

    /** Settings fixed by constitution Article III; naming one is an error, not a no-op. */
    static final Map<String, String> FIXED = new LinkedHashMap<>();

    static {
        FIXED.put("sandboxPolicy", "UNTRUSTED");
        FIXED.put("hostAccess", "UNTRUSTED");
        FIXED.put("engine.SpawnIsolate", "true");
        FIXED.put("engine.IsolateMode", "internal");
        FIXED.put("sandbox.MaxThreads", "1");
    }

    static final Set<String> PROFILES = Set.of("main", "subagent");

    /**
     * Polyglot 25.0 names the plan's {@code engine.MemoryProtection} {@code engine.IsolateMemoryProtection}.
     * The config key keeps the plan's name; only the option passed to the engine changes.
     */
    static final String MEMORY_PROTECTION_OPTION = "engine.IsolateMemoryProtection";

    private static final Pattern SIZE = Pattern.compile("([0-9]+)(B|KB|MB|GB)");
    private static final Pattern DURATION = Pattern.compile("([0-9]+)(ms|s|m|h)");

    /** One profile's resolved values, keyed as in {@link #KEYS}, in canonical string form. */
    record Profile(String name, Map<String, String> values) {

        /** Options for {@code Engine.Builder}; {@code auto} memory protection leaves GraalVM's default. */
        Map<String, String> engineOptions() {
            Map<String, String> m = new LinkedHashMap<>();
            values.forEach((k, v) -> {
                if (k.equals("engine.MemoryProtection")) {
                    if (!v.equals("auto")) {
                        m.put(MEMORY_PROTECTION_OPTION, v);
                    }
                } else if (k.startsWith("engine.")) {
                    m.put(k, v);
                }
            });
            return m;
        }

        /** Options for {@code Context.Builder}: every {@code sandbox.*} limit. */
        Map<String, String> contextOptions() {
            Map<String, String> m = new LinkedHashMap<>();
            values.forEach((k, v) -> {
                if (k.startsWith("sandbox.")) {
                    m.put(k, v);
                }
            });
            return m;
        }

        Duration wallClock() {
            return parseDuration(values.get("wallClock"));
        }

        /** Every value in effect, configurable and fixed, for printing with probe results. */
        Map<String, Object> effective() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("profile", name);
            m.putAll(values);
            m.putAll(FIXED);
            return m;
        }
    }

    private final Map<String, Map<String, String>> profiles;
    private final Path configFile;
    private final List<String> overrides;

    private SandboxConfig(Map<String, Map<String, String>> profiles, Path configFile, List<String> overrides) {
        this.profiles = profiles;
        this.configFile = configFile;
        this.overrides = List.copyOf(overrides);
    }

    /** The same sources plus more {@code --set} values, applied last; used by probes that compare settings. */
    SandboxConfig with(String... moreOverrides) throws ConfigException {
        List<String> all = new java.util.ArrayList<>(overrides);
        all.addAll(List.of(moreOverrides));
        return load(configFile, all);
    }

    /** Built-in defaults only. */
    static SandboxConfig defaults() throws ConfigException {
        return load(null, List.of());
    }

    /**
     * @param configFile optional {@code --config} file
     * @param overrides {@code --set} values: {@code <profile>.<key>=<value>}, e.g. {@code main.sandbox.MaxCPUTime=5s}
     */
    static SandboxConfig load(Path configFile, List<String> overrides) throws ConfigException {
        Map<String, Map<String, String>> profiles = new LinkedHashMap<>();
        for (String p : List.of("main", "subagent")) {
            profiles.put(p, new LinkedHashMap<>());
        }
        apply(profiles, readDefaults(), "built-in defaults");
        if (configFile != null) {
            String text;
            try {
                text = Files.readString(configFile, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new ConfigException("cannot read config file " + configFile + ": " + e.getMessage());
            }
            apply(profiles, parse(text, configFile.toString()), configFile.toString());
        }
        for (String o : overrides) {
            applyOverride(profiles, o);
        }
        for (Map.Entry<String, Map<String, String>> e : profiles.entrySet()) {
            checkComplete(e.getKey(), e.getValue());
        }
        return new SandboxConfig(profiles, configFile, overrides);
    }

    Profile profile(String name) {
        Map<String, String> v = profiles.get(name);
        if (v == null) {
            throw new IllegalArgumentException("unknown profile " + name);
        }
        Map<String, String> ordered = new LinkedHashMap<>();
        for (String k : KEYS.keySet()) {
            ordered.put(k, v.get(k));
        }
        return new Profile(name, java.util.Collections.unmodifiableMap(ordered));
    }

    // ---------------------------------------------------------------------------------------------

    private static Object readDefaults() throws ConfigException {
        try (InputStream in = SandboxConfig.class.getResourceAsStream("/sandbox-defaults.json")) {
            if (in == null) {
                throw new ConfigException("built-in sandbox-defaults.json is missing from the binary");
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), "built-in defaults");
        } catch (IOException e) {
            throw new ConfigException("cannot read built-in defaults: " + e.getMessage());
        }
    }

    private static Object parse(String text, String source) throws ConfigException {
        try {
            return Json.parse(text);
        } catch (Json.ParseException e) {
            throw new ConfigException(source + ": " + e.getMessage());
        }
    }

    private static void apply(Map<String, Map<String, String>> profiles, Object root, String source)
            throws ConfigException {
        if (!(root instanceof Map<?, ?> top)) {
            throw new ConfigException(source + ": top level must be an object");
        }
        for (Map.Entry<?, ?> t : top.entrySet()) {
            if (!"sandbox".equals(t.getKey())) {
                throw new ConfigException(source + ": unknown key \"" + t.getKey() + "\" (only \"sandbox\" is accepted)");
            }
            if (!(t.getValue() instanceof Map<?, ?> sandbox)) {
                throw new ConfigException(source + ": \"sandbox\" must be an object");
            }
            for (Map.Entry<?, ?> p : sandbox.entrySet()) {
                String profile = String.valueOf(p.getKey());
                if (!PROFILES.contains(profile)) {
                    throw new ConfigException(source + ": unknown profile \"" + profile + "\" (expected main or subagent)");
                }
                if (!(p.getValue() instanceof Map<?, ?> values)) {
                    throw new ConfigException(source + ": sandbox." + profile + " must be an object");
                }
                for (Map.Entry<?, ?> kv : values.entrySet()) {
                    String key = String.valueOf(kv.getKey());
                    Object raw = kv.getValue();
                    if (raw == null || raw instanceof Map || raw instanceof List) {
                        throw new ConfigException(source + ": " + profile + "." + key + " must be a string or number");
                    }
                    put(profiles.get(profile), profile, key, String.valueOf(raw), source);
                }
            }
        }
    }

    private static void applyOverride(Map<String, Map<String, String>> profiles, String override)
            throws ConfigException {
        int eq = override.indexOf('=');
        int dot = override.indexOf('.');
        if (eq < 0 || dot < 0 || dot > eq) {
            throw new ConfigException("--set " + override + ": expected <profile>.<key>=<value>");
        }
        String profile = override.substring(0, dot);
        if (!PROFILES.contains(profile)) {
            throw new ConfigException("--set " + override + ": unknown profile \"" + profile + "\" (expected main or subagent)");
        }
        put(profiles.get(profile), profile, override.substring(dot + 1, eq), override.substring(eq + 1), "--set");
    }

    private static void put(Map<String, String> into, String profile, String key, String value, String source)
            throws ConfigException {
        String where = source + ": " + profile + "." + key;
        if (FIXED.containsKey(key)) {
            throw new ConfigException(where + " is fixed at " + FIXED.get(key) + " and cannot be changed");
        }
        Kind kind = KEYS.get(key);
        if (kind == null) {
            throw new ConfigException(where + " is not a known setting (known: " + String.join(", ", KEYS.keySet()) + ")");
        }
        into.put(key, canonical(kind, value.trim(), where));
    }

    private static String canonical(Kind kind, String v, String where) throws ConfigException {
        switch (kind) {
            case SIZE -> {
                Matcher m = SIZE.matcher(v.toUpperCase());
                if (!m.matches() || positive(m.group(1), where) == 0) {
                    throw new ConfigException(where + "=" + v + ": expected a size above zero such as 256KB or 512MB");
                }
                return Long.parseLong(m.group(1)) + m.group(2);
            }
            case DURATION -> {
                Matcher m = DURATION.matcher(v);
                if (!m.matches() || positive(m.group(1), where) == 0) {
                    throw new ConfigException(where + "=" + v + ": expected a duration above zero such as 500ms, 30s or 15m");
                }
                return Long.parseLong(m.group(1)) + m.group(2);
            }
            case COUNT -> {
                if (!v.matches("[0-9]+") || positive(v, where) == 0) {
                    throw new ConfigException(where + "=" + v + ": expected a whole number above zero");
                }
                return Long.toString(Long.parseLong(v));
            }
            case AUTO_BOOLEAN -> {
                if (!v.equals("auto") && !v.equals("true") && !v.equals("false")) {
                    throw new ConfigException(where + "=" + v + ": expected auto, true or false");
                }
                return v;
            }
        }
        throw new AssertionError(kind);
    }

    private static long positive(String digits, String where) throws ConfigException {
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            throw new ConfigException(where + ": number too large");
        }
    }

    private static void checkComplete(String profile, Map<String, String> values) throws ConfigException {
        for (String k : KEYS.keySet()) {
            if (!values.containsKey(k)) {
                throw new ConfigException("profile " + profile + " has no value for " + k + "; a limit cannot be removed");
            }
        }
        long heap = bytes(values.get("sandbox.MaxHeapMemory"));
        long isolate = bytes(values.get("engine.MaxIsolateMemory"));
        if (heap >= isolate) {
            throw new ConfigException("profile " + profile + ": sandbox.MaxHeapMemory (" + values.get("sandbox.MaxHeapMemory")
                    + ") must be below engine.MaxIsolateMemory (" + values.get("engine.MaxIsolateMemory") + ")");
        }
    }

    static long bytes(String size) {
        Matcher m = SIZE.matcher(size);
        if (!m.matches()) {
            throw new IllegalArgumentException(size);
        }
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "B" -> n;
            case "KB" -> n << 10;
            case "MB" -> n << 20;
            default -> n << 30;
        };
    }

    static Duration parseDuration(String d) {
        Matcher m = DURATION.matcher(d);
        if (!m.matches()) {
            throw new IllegalArgumentException(d);
        }
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "ms" -> Duration.ofMillis(n);
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            default -> Duration.ofHours(n);
        };
    }
}
