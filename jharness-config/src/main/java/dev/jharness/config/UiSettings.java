package dev.jharness.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The {@code ui} section: {@code events}, what the TUI shows of the event stream ({@code none}, {@code steps},
 * {@code all}); {@code thinking}, whether it shows the model's thinking text ({@code hide}, {@code show});
 * {@code statusLine}, the status line's items in order (spec 042): {@code true} for all of them, {@code false}
 * (an empty list) for no status line, or a list of item names.
 */
public record UiSettings(String events, String thinking, List<String> statusLine) {

    public static final List<String> EVENT_LEVELS = List.of("none", "steps", "all");
    public static final List<String> THINKING = List.of("hide", "show");
    /** The status line's built-in items, in their default order. */
    public static final List<String> STATUS_ITEMS = List.of("model", "context", "mode", "agents", "cache", "turn", "workspace");

    public UiSettings {
        statusLine = List.copyOf(statusLine);
    }

    public UiSettings(String events, String thinking) {
        this(events, thinking, STATUS_ITEMS);
    }

    public static UiSettings defaults() {
        return new UiSettings("steps", "hide", STATUS_ITEMS);
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
        List<String> status = STATUS_ITEMS;
        for (Map.Entry<String, JsonNode> e : section.properties()) {
            JsonNode v = e.getValue();
            if (e.getKey().equals("statusLine")) {
                status = statusLine(v, source);
                continue;
            }
            List<String> allowed = switch (e.getKey()) {
                case "events" -> EVENT_LEVELS;
                case "thinking" -> THINKING;
                default -> throw new ConfigException(source + ": ui." + e.getKey() + " is not a known setting (known: events, thinking, statusLine)");
            };
            if (!v.isTextual() || !allowed.contains(v.asText())) {
                throw new ConfigException(source + ": ui." + e.getKey() + "=" + v + ": expected one of " + String.join(", ", allowed));
            }
            if (e.getKey().equals("events")) {
                events = v.asText();
            } else {
                thinking = v.asText();
            }
        }
        return new UiSettings(events, thinking, status);
    }

    private static List<String> statusLine(JsonNode v, String source) throws ConfigException {
        if (v.isBoolean()) {
            return v.asBoolean() ? STATUS_ITEMS : List.of();
        }
        String expected = source + ": ui.statusLine: expected true, false or a list of items (" + String.join(", ", STATUS_ITEMS) + ")";
        if (!v.isArray()) {
            throw new ConfigException(expected);
        }
        List<String> items = new ArrayList<>();
        for (JsonNode i : v) {
            if (!i.isTextual() || !STATUS_ITEMS.contains(i.asText())) {
                throw new ConfigException(expected + "; got " + i);
            }
            if (items.contains(i.asText())) {
                throw new ConfigException(source + ": ui.statusLine lists " + i.asText() + " twice");
            }
            items.add(i.asText());
        }
        return items;
    }
}
