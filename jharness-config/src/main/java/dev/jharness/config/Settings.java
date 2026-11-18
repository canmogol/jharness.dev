package dev.jharness.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import dev.jharness.events.Json;

/**
 * User settings from {@code ~/.jharness/settings.json}: {@code model}, {@code models}, {@code sandbox}
 * and {@code timeouts}. Project and session layers come with the layered-settings feature. Unknown
 * keys and bad values throw {@link ConfigException}, which stops start-up.
 */
public record Settings(ModelProfiles models, SandboxSettings sandbox, Timeouts timeouts) {

    private static final Set<String> TOP = Set.of("model", "models", "sandbox", "timeouts");

    public static Settings defaults() {
        return new Settings(ModelProfiles.empty(), SandboxSettings.defaults(), Timeouts.defaults());
    }

    public static Path userFile(Path home) {
        return home.resolve(".jharness").resolve("settings.json");
    }

    /** Loads {@code <home>/.jharness/settings.json}; a missing file means defaults. */
    public static Settings load(Path home) throws ConfigException {
        Path f = userFile(home);
        String text;
        try {
            text = Files.readString(f, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return defaults();
        } catch (IOException e) {
            throw new ConfigException("cannot read " + f + ": " + e.getMessage());
        }
        return parse(text, f.toString());
    }

    public static Settings parse(String text, String source) throws ConfigException {
        JsonNode root;
        try {
            root = Json.parse(text);
        } catch (IllegalArgumentException e) {
            throw new ConfigException(source + ": " + e.getMessage());
        }
        if (!root.isObject()) {
            throw new ConfigException(source + ": top level must be an object");
        }
        for (Map.Entry<String, JsonNode> e : root.properties()) {
            if (!TOP.contains(e.getKey())) {
                throw new ConfigException(source + ": unknown key \"" + e.getKey() + "\" (known: model, models, sandbox, timeouts)");
            }
        }
        return new Settings(
                ModelProfiles.parse(root.get("model"), root.get("models"), source),
                SandboxSettings.parse(root.get("sandbox"), source),
                Timeouts.parse(root.get("timeouts"), source));
    }
}
