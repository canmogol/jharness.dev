package dev.jharness.config;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The {@code ui} section: {@code events}, what the TUI shows of the event stream ({@code none}, {@code steps},
 * {@code all}); {@code thinking}, whether it shows the model's thinking text ({@code hide}, {@code show}).
 */
public record UiSettings(String events, String thinking) {

    public static final List<String> EVENT_LEVELS = List.of("none", "steps", "all");
    public static final List<String> THINKING = List.of("hide", "show");

    public static UiSettings defaults() {
        return new UiSettings("steps", "hide");
    }

    static UiSettings parse(JsonNode section, String source) throws ConfigException {
        if (section == null) {
            return defaults();
        }
        if (!section.isObject()) {
            throw new ConfigException(source + ": \"ui\" must be an object");
        }
        String events = defaults().events();
        String thinking = defaults().thinking();
        for (Map.Entry<String, JsonNode> e : section.properties()) {
            List<String> allowed = switch (e.getKey()) {
                case "events" -> EVENT_LEVELS;
                case "thinking" -> THINKING;
                default -> throw new ConfigException(source + ": ui." + e.getKey() + " is not a known setting (known: events, thinking)");
            };
            JsonNode v = e.getValue();
            if (!v.isTextual() || !allowed.contains(v.asText())) {
                throw new ConfigException(source + ": ui." + e.getKey() + "=" + v + ": expected one of " + String.join(", ", allowed));
            }
            if (e.getKey().equals("events")) {
                events = v.asText();
            } else {
                thinking = v.asText();
            }
        }
        return new UiSettings(events, thinking);
    }
}
