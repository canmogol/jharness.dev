package dev.jharness.config;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/** The {@code ui} section: {@code events}, what the TUI shows of the event stream ({@code none}, {@code steps}, {@code all}). */
public record UiSettings(String events) {

    public static final List<String> EVENT_LEVELS = List.of("none", "steps", "all");

    public static UiSettings defaults() {
        return new UiSettings("steps");
    }

    static UiSettings parse(JsonNode section, String source) throws ConfigException {
        if (section == null) {
            return defaults();
        }
        if (!section.isObject()) {
            throw new ConfigException(source + ": \"ui\" must be an object");
        }
        String events = defaults().events();
        for (Map.Entry<String, JsonNode> e : section.properties()) {
            if (!e.getKey().equals("events")) {
                throw new ConfigException(source + ": ui." + e.getKey() + " is not a known setting (known: events)");
            }
            JsonNode v = e.getValue();
            if (!v.isTextual() || !EVENT_LEVELS.contains(v.asText())) {
                throw new ConfigException(source + ": ui.events=" + v + ": expected one of " + String.join(", ", EVENT_LEVELS));
            }
            events = v.asText();
        }
        return new UiSettings(events);
    }
}
