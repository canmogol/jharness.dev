package dev.jharness.config;

import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/** The {@code permissions} section: {@code mode} is {@code ask} (default), {@code accept-edits}, {@code plan} or {@code bypass} (D-041). */
public record PermissionSettings(String mode) {

    public static final Set<String> MODES = Set.of("ask", "accept-edits", "plan", "bypass");

    public static PermissionSettings defaults() {
        return new PermissionSettings("ask");
    }

    static PermissionSettings parse(JsonNode section, String source) throws ConfigException {
        if (section == null) {
            return defaults();
        }
        if (!section.isObject()) {
            throw new ConfigException(source + ": \"permissions\" must be an object");
        }
        String mode = "ask";
        for (Map.Entry<String, JsonNode> e : section.properties()) {
            if (!e.getKey().equals("mode")) {
                throw new ConfigException(source + ": permissions." + e.getKey() + " is not a known setting (known: mode)");
            }
            if (!e.getValue().isTextual() || !MODES.contains(e.getValue().asText())) {
                throw new ConfigException(source + ": permissions.mode=" + e.getValue().asText()
                        + ": expected ask, accept-edits, plan or bypass");
            }
            mode = e.getValue().asText();
        }
        return new PermissionSettings(mode);
    }
}
