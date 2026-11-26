package dev.jharness.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The {@code files} section (D-041): {@code mounts} are absolute paths of folders the agent may read
 * but not change; {@code deny} adds globs to the built-in deny list; {@code checkpointDays} (spec 057) is how
 * long a session's saved files for {@code /undo} are kept, 0 for ever.
 */
public record FileSettings(List<Path> mounts, List<String> deny, int checkpointDays) {

    /** Days a finished session's checkpoints are kept by default (spec 057). */
    public static final int DEFAULT_CHECKPOINT_DAYS = 30;
    public static final int MAX_CHECKPOINT_DAYS = 3650;

    public FileSettings {
        mounts = List.copyOf(mounts);
        deny = List.copyOf(deny);
    }

    public FileSettings(List<Path> mounts, List<String> deny) {
        this(mounts, deny, DEFAULT_CHECKPOINT_DAYS);
    }

    public static FileSettings defaults() {
        return new FileSettings(List.of(), List.of());
    }

    static FileSettings parse(JsonNode section, String source) throws ConfigException {
        if (section == null) {
            return defaults();
        }
        if (!section.isObject()) {
            throw new ConfigException(source + ": \"files\" must be an object");
        }
        List<Path> mounts = new ArrayList<>();
        List<String> deny = new ArrayList<>();
        int days = DEFAULT_CHECKPOINT_DAYS;
        for (Map.Entry<String, JsonNode> e : section.properties()) {
            String where = source + ": files." + e.getKey();
            if (e.getKey().equals("checkpointDays")) {
                JsonNode v = e.getValue();
                if (!v.canConvertToInt() || !v.isIntegralNumber() || v.asInt() < 0 || v.asInt() > MAX_CHECKPOINT_DAYS) {
                    throw new ConfigException(where + "=" + v + ": expected a whole number of days from 0 (keep for ever) to " + MAX_CHECKPOINT_DAYS);
                }
                days = v.asInt();
                continue;
            }
            if (!e.getValue().isArray()) {
                throw new ConfigException(where + " must be a list of strings");
            }
            for (JsonNode n : e.getValue()) {
                if (!n.isTextual() || n.asText().isBlank()) {
                    throw new ConfigException(where + " entries must be non-empty strings");
                }
                String v = n.asText();
                switch (e.getKey()) {
                    case "mounts" -> {
                        Path p = Path.of(v);
                        if (!p.isAbsolute()) {
                            throw new ConfigException(where + ": " + v + " must be an absolute path");
                        }
                        if (!Files.isDirectory(p)) {
                            throw new ConfigException(where + ": " + v + " is not a folder");
                        }
                        try {
                            mounts.add(p.toRealPath());
                        } catch (IOException ex) {
                            throw new ConfigException(where + ": cannot read " + v + ": " + ex.getMessage());
                        }
                    }
                    case "deny" -> {
                        if (v.indexOf('\0') >= 0) {
                            throw new ConfigException(where + ": glob contains a NUL character");
                        }
                        deny.add(v);
                    }
                    default -> throw new ConfigException(where + " is not a known setting (known: mounts, deny, checkpointDays)");
                }
            }
        }
        return new FileSettings(mounts, deny, days);
    }
}
