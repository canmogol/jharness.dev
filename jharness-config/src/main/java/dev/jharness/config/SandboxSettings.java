package dev.jharness.config;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The {@code sandbox} section: limit values per profile ({@code main}, {@code subagent}), D-003.
 * A value can be changed but not removed; the policy, host access, isolate mode and thread count are
 * fixed and rejected if named. Defaults are those in {@code product/tech-stack.md}.
 */
public final class SandboxSettings {

    enum Kind { SIZE, DURATION, COUNT, PROTECTION }

    static final Map<String, Kind> KEYS = new LinkedHashMap<>();
    static final Map<String, String> FIXED = new LinkedHashMap<>();
    static final Set<String> PROFILES = Set.of("main", "subagent");

    static {
        KEYS.put("engine.MaxIsolateMemory", Kind.SIZE);
        KEYS.put("engine.MemoryProtection", Kind.PROTECTION);
        KEYS.put("sandbox.MaxHeapMemory", Kind.SIZE);
        KEYS.put("sandbox.MaxCPUTime", Kind.DURATION);
        KEYS.put("sandbox.MaxASTDepth", Kind.COUNT);
        KEYS.put("sandbox.MaxOutputStreamSize", Kind.SIZE);
        KEYS.put("sandbox.MaxErrorStreamSize", Kind.SIZE);
        KEYS.put("wallClock", Kind.DURATION);
        KEYS.put("watchdog.engineCloseAfter", Kind.DURATION);
        KEYS.put("watchdog.failAfter", Kind.DURATION);

        FIXED.put("sandboxPolicy", "UNTRUSTED");
        FIXED.put("hostAccess", "UNTRUSTED");
        FIXED.put("engine.SpawnIsolate", "true");
        FIXED.put("engine.IsolateMode", "internal");
        FIXED.put("sandbox.MaxThreads", "1");
    }

    private static Map<String, String> defaults(String profile) {
        boolean main = profile.equals("main");
        Map<String, String> m = new LinkedHashMap<>();
        m.put("engine.MaxIsolateMemory", main ? "512MB" : "256MB");
        m.put("engine.MemoryProtection", "off");
        m.put("sandbox.MaxHeapMemory", main ? "256MB" : "128MB");
        m.put("sandbox.MaxCPUTime", "30s");
        m.put("sandbox.MaxASTDepth", "100");
        m.put("sandbox.MaxOutputStreamSize", "256KB");
        m.put("sandbox.MaxErrorStreamSize", "256KB");
        m.put("wallClock", "15m");
        m.put("watchdog.engineCloseAfter", "10s");
        m.put("watchdog.failAfter", "30s");
        return m;
    }

    /** Memory protection setting (D-036). */
    public enum MemoryProtection { OFF, AUTO, ON }

    /** One profile's values, keyed as in {@link #KEYS}. */
    public record Profile(String name, Map<String, String> values) {

        /** Engine limit options; memory protection is handled by the isolate manager. */
        public Map<String, String> engineOptions() {
            Map<String, String> m = new LinkedHashMap<>();
            values.forEach((k, v) -> {
                if (k.startsWith("engine.") && !k.equals("engine.MemoryProtection")) {
                    m.put(k, v);
                }
            });
            return m;
        }

        /** Every {@code sandbox.*} limit, for the context builder. */
        public Map<String, String> contextOptions() {
            Map<String, String> m = new LinkedHashMap<>();
            values.forEach((k, v) -> {
                if (k.startsWith("sandbox.")) {
                    m.put(k, v);
                }
            });
            return m;
        }

        public MemoryProtection memoryProtection() {
            return MemoryProtection.valueOf(values.get("engine.MemoryProtection").toUpperCase());
        }

        public Duration wallClock() {
            return Units.parseDuration(values.get("wallClock"));
        }

        public Duration engineCloseAfter() {
            return Units.parseDuration(values.get("watchdog.engineCloseAfter"));
        }

        public Duration failAfter() {
            return Units.parseDuration(values.get("watchdog.failAfter"));
        }

        /** A copy with some values replaced, validated like settings; for tests and probes. */
        public Profile with(Map<String, String> changes) throws ConfigException {
            Map<String, String> v = new LinkedHashMap<>(values);
            for (Map.Entry<String, String> e : changes.entrySet()) {
                put(v, name, e.getKey(), e.getValue(), "override");
            }
            check(name, v);
            return new Profile(name, Collections.unmodifiableMap(v));
        }
    }

    private final Map<String, Profile> profiles;

    private SandboxSettings(Map<String, Profile> profiles) {
        this.profiles = profiles;
    }

    public static SandboxSettings defaults() {
        try {
            return parse(null, "defaults");
        } catch (ConfigException e) {
            throw new IllegalStateException(e);
        }
    }

    static SandboxSettings parse(JsonNode section, String source) throws ConfigException {
        Map<String, Map<String, String>> raw = new LinkedHashMap<>();
        raw.put("main", defaults("main"));
        raw.put("subagent", defaults("subagent"));
        if (section != null) {
            if (!section.isObject()) {
                throw new ConfigException(source + ": \"sandbox\" must be an object");
            }
            for (Map.Entry<String, JsonNode> p : section.properties()) {
                String profile = p.getKey();
                if (!PROFILES.contains(profile)) {
                    throw new ConfigException(source + ": unknown sandbox profile \"" + profile + "\" (expected main or subagent)");
                }
                if (!p.getValue().isObject()) {
                    throw new ConfigException(source + ": sandbox." + profile + " must be an object");
                }
                for (Map.Entry<String, JsonNode> kv : p.getValue().properties()) {
                    JsonNode v = kv.getValue();
                    if (!v.isTextual() && !v.isNumber()) {
                        throw new ConfigException(source + ": sandbox." + profile + "." + kv.getKey() + " must be a string or number");
                    }
                    put(raw.get(profile), profile, kv.getKey(), v.asText(), source);
                }
            }
        }
        Map<String, Profile> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> e : raw.entrySet()) {
            check(e.getKey(), e.getValue());
            out.put(e.getKey(), new Profile(e.getKey(), Collections.unmodifiableMap(e.getValue())));
        }
        return new SandboxSettings(out);
    }

    public Profile profile(String name) {
        Profile p = profiles.get(name);
        if (p == null) {
            throw new IllegalArgumentException("unknown sandbox profile " + name);
        }
        return p;
    }

    private static void put(Map<String, String> into, String profile, String key, String value, String source)
            throws ConfigException {
        String where = source + ": sandbox." + profile + "." + key;
        if (FIXED.containsKey(key)) {
            throw new ConfigException(where + " is fixed at " + FIXED.get(key) + " and cannot be changed");
        }
        Kind kind = KEYS.get(key);
        if (kind == null) {
            throw new ConfigException(where + " is not a known setting (known: " + String.join(", ", KEYS.keySet()) + ")");
        }
        String canonical = switch (kind) {
            case SIZE -> Units.size(value);
            case DURATION -> Units.duration(value);
            case COUNT -> value.trim().matches("[0-9]{1,9}") && Long.parseLong(value.trim()) > 0
                    ? Long.toString(Long.parseLong(value.trim())) : null;
            case PROTECTION -> Set.of("off", "auto", "on").contains(value.trim()) ? value.trim() : null;
        };
        if (canonical == null) {
            String expected = switch (kind) {
                case SIZE -> "a size above zero such as 256KB or 512MB";
                case DURATION -> "a duration above zero such as 500ms, 30s or 15m";
                case COUNT -> "a whole number above zero";
                case PROTECTION -> "off, auto or on";
            };
            throw new ConfigException(where + "=" + value + ": expected " + expected);
        }
        into.put(key, canonical);
    }

    private static void check(String profile, Map<String, String> v) throws ConfigException {
        if (Units.bytes(v.get("sandbox.MaxHeapMemory")) >= Units.bytes(v.get("engine.MaxIsolateMemory"))) {
            throw new ConfigException("sandbox." + profile + ": sandbox.MaxHeapMemory (" + v.get("sandbox.MaxHeapMemory")
                    + ") must be below engine.MaxIsolateMemory (" + v.get("engine.MaxIsolateMemory") + ")");
        }
    }
}
