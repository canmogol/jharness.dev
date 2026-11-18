package dev.jharness.events;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Stamps, validates and publishes events (D-006). Publishing is serialised, and subscribers are
 * called in subscription order on the publishing thread, so every subscriber sees the same order.
 */
public final class EventBus {

    private final String session;
    private final Clock clock;
    private final List<Consumer<Event>> subscribers = new CopyOnWriteArrayList<>();
    private final SecureRandom random = new SecureRandom();
    private long lastMillis;
    private int sequence;

    public EventBus(String session, Clock clock) {
        this.session = session;
        this.clock = clock;
    }

    public EventBus(String session) {
        this(session, Clock.systemUTC());
    }

    /** A new random session id. */
    public static String newSessionId() {
        byte[] b = new byte[6];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder("s-").append(Long.toString(System.currentTimeMillis(), 36)).append('-');
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    public String session() {
        return session;
    }

    public void subscribe(Consumer<Event> subscriber) {
        subscribers.add(subscriber);
    }

    /** Publishes a host event; the body must carry the type's required fields. */
    public Event publish(Origin origin, String type, ObjectNode body) {
        ObjectNode copy = body.deepCopy();
        Event.ENVELOPE.forEach(copy::remove);
        EventTypes.validateHost(type, copy);
        return emit(origin, "host", type, copy);
    }

    /**
     * Publishes an event a guest sent as a JSON string through {@code api.emit}. The size is checked
     * before parsing; the type must be {@code agent.*} or {@code custom.*}; envelope fields the guest
     * sent are replaced by the host's.
     */
    public Event publishGuest(Origin origin, String raw) {
        if (raw == null) {
            throw new EventException("event is null");
        }
        int size = raw.getBytes(StandardCharsets.UTF_8).length;
        if (size > EventTypes.MAX_GUEST_EVENT_BYTES) {
            throw new EventException("event is " + size + " bytes; the limit is " + EventTypes.MAX_GUEST_EVENT_BYTES);
        }
        ObjectNode body;
        try {
            body = Json.parseObject(raw);
        } catch (IllegalArgumentException e) {
            throw new EventException("event is not a JSON object: " + e.getMessage());
        }
        JsonNode t = body.get("type");
        String type = t != null && t.isTextual() ? t.asText() : null;
        if (!EventTypes.isGuestType(type)) {
            throw new EventException("guest events must have type agent.* or custom.*, got " + (type == null ? "none" : type));
        }
        Event.ENVELOPE.forEach(body::remove);
        return emit(origin, "guest", type, body);
    }

    private synchronized Event emit(Origin origin, String source, String type, ObjectNode body) {
        Instant now = clock.instant();
        Event e = new Event(nextId(now.toEpochMilli()), now, session, origin.agent(), origin.turn(), source, type, body);
        for (Consumer<Event> s : subscribers) {
            s.accept(e);
        }
        return e;
    }

    /** Time-ordered id: 12 hex digits of milliseconds, 4 of sequence, 6 random. */
    private String nextId(long millis) {
        if (millis <= lastMillis) {
            millis = lastMillis;
            sequence++;
        } else {
            lastMillis = millis;
            sequence = 0;
        }
        return String.format("%012x%04x%06x", millis, sequence & 0xffff, random.nextInt(1 << 24));
    }
}
