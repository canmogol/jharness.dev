package dev.jharness.events;

import java.time.Instant;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One event: the host-stamped envelope plus the type's body fields (D-006, constitution Article VIII).
 *
 * @param source {@code host} or {@code guest}
 * @param body the type's fields; never contains envelope names
 */
public record Event(String id, Instant ts, String session, String agent, int turn, String source, String type,
        ObjectNode body) {

    public static final java.util.Set<String> ENVELOPE =
            java.util.Set.of("id", "ts", "session", "agent", "turn", "source", "type");

    /** The event as one JSON object, envelope first. */
    public ObjectNode toJson() {
        ObjectNode o = Json.object();
        o.put("id", id);
        o.put("ts", ts.toString());
        o.put("session", session);
        o.put("agent", agent);
        o.put("turn", turn);
        o.put("source", source);
        o.put("type", type);
        o.setAll(body);
        return o;
    }

    public String toJsonLine() {
        return Json.write(toJson());
    }
}
