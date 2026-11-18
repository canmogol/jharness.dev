package dev.jharness.events;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class EventBusTest {

    private final EventBus bus = new EventBus("s-test");

    @Test
    void stampsEnvelope() {
        List<Event> seen = new ArrayList<>();
        bus.subscribe(seen::add);
        Event e = bus.publish(new Origin("main", 3), "api.call", Json.object().put("api", "readFile").put("status", "ok"));
        assertEquals(List.of(e), seen);
        var j = e.toJson();
        for (String f : List.of("id", "ts", "session", "agent", "turn", "source", "type")) {
            assertTrue(j.has(f), f);
        }
        assertEquals("s-test", j.get("session").asText());
        assertEquals("main", j.get("agent").asText());
        assertEquals(3, j.get("turn").asInt());
        assertEquals("host", j.get("source").asText());
        assertEquals("readFile", j.get("api").asText());
    }

    @Test
    void hostEventMissingFieldIsRejected() {
        assertThrows(EventException.class, () -> bus.publish(Origin.main(1), "api.call", Json.object().put("api", "x")));
    }

    @Test
    void unknownHostTypeIsRejected() {
        assertThrows(EventException.class, () -> bus.publish(Origin.main(1), "file.deleted", Json.object()));
    }

    @Test
    void hostBodyCannotOverrideEnvelope() {
        Event e = bus.publish(Origin.main(1), "model.delta", Json.object().put("text", "a").put("session", "evil"));
        assertEquals("s-test", e.toJson().get("session").asText());
    }

    @Test
    void guestEventsAllowOnlyAgentAndCustomTypes() {
        Event ok = bus.publishGuest(Origin.main(1), "{\"type\":\"agent.note\",\"text\":\"hi\"}");
        assertEquals("guest", ok.source());
        assertEquals("agent.note", ok.type());
        bus.publishGuest(Origin.main(1), "{\"type\":\"custom.x-y_z\"}");
        for (String bad : List.of("{\"type\":\"api.call\",\"api\":\"x\",\"status\":\"ok\"}", "{\"type\":\"agent.\"}",
                "{\"text\":\"no type\"}", "{\"type\":\"permission.decided\"}", "[1]", "not json", "{\"type\":7}")) {
            assertThrows(EventException.class, () -> bus.publishGuest(Origin.main(1), bad), bad);
        }
    }

    @Test
    void guestCannotForgeEnvelope() {
        Event e = bus.publishGuest(new Origin("main", 2),
                "{\"type\":\"agent.x\",\"source\":\"host\",\"session\":\"other\",\"turn\":99,\"id\":\"forged\"}");
        var j = e.toJson();
        assertEquals("guest", j.get("source").asText());
        assertEquals("s-test", j.get("session").asText());
        assertEquals(2, j.get("turn").asInt());
        assertNotEquals("forged", j.get("id").asText());
    }

    @Test
    void guestEventSizeLimit() {
        String pad = "x".repeat(EventTypes.MAX_GUEST_EVENT_BYTES);
        EventException ex = assertThrows(EventException.class,
                () -> bus.publishGuest(Origin.main(1), "{\"type\":\"agent.big\",\"t\":\"" + pad + "\"}"));
        assertTrue(ex.getMessage().contains("limit"));
        String fits = "x".repeat(EventTypes.MAX_GUEST_EVENT_BYTES - 40);
        bus.publishGuest(Origin.main(1), "{\"type\":\"agent.big\",\"t\":\"" + fits + "\"}");
    }

    @Test
    void subscribersSeeTheSameOrderAcrossThreads() throws Exception {
        List<String> a = new ArrayList<>();
        List<String> b = new ArrayList<>();
        bus.subscribe(e -> a.add(e.id()));
        bus.subscribe(e -> b.add(e.id()));
        int threads = 8;
        int each = 200;
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> ts = new ArrayList<>();
        AtomicInteger n = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            ts.add(Thread.ofPlatform().start(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < each; i++) {
                    bus.publish(Origin.main(n.incrementAndGet()), "model.delta", Json.object().put("text", "t"));
                }
            }));
        }
        go.countDown();
        for (Thread t : ts) {
            t.join();
        }
        assertEquals(threads * each, a.size());
        assertEquals(a, b);
        List<String> sorted = new ArrayList<>(a);
        sorted.sort(null);
        assertEquals(sorted, a, "ids are time-ordered in publish order");
    }
}
