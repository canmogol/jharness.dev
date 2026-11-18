package dev.jharness.llm;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.events.Event;
import dev.jharness.events.EventBus;
import dev.jharness.events.Origin;

class ReplayProviderTest {

    @TempDir
    Path tmp;
    final EventBus bus = new EventBus("s-replay");
    final List<Event> events = new ArrayList<>();
    static final ChatRequest REQ = new ChatRequest("sys", List.of(ChatMessage.user("hi")), 0);

    ReplayProviderTest() {
        bus.subscribe(events::add);
    }

    @Test
    void playsBackRepliesAndErrorsThenRunsOut() throws Exception {
        Path f = tmp.resolve("r.jsonl");
        Files.writeString(f, "{\"reply\":\"line one\\nline two\"}\n\n{\"error\":\"rate limited\"}\n");
        ReplayProvider p = ReplayProvider.load(f, bus, () -> Origin.main(1));
        List<String> deltas = new ArrayList<>();
        assertEquals("line one\nline two", p.stream(REQ, deltas::add).text());
        assertEquals(List.of("line one\n", "line two"), deltas);
        assertEquals(List.of("model.request", "model.delta", "model.delta", "model.completed"), events.stream().map(Event::type).toList());
        assertEquals("rate limited", assertThrows(ModelException.class, () -> p.stream(REQ, d -> {})).getMessage());
        ModelException out = assertThrows(ModelException.class, () -> p.stream(REQ, d -> {}));
        assertTrue(out.getMessage().contains("no more replies"), out.getMessage());
        assertEquals("model.error", events.getLast().type());
        assertEquals(3, p.requests().size());
    }

    @Test
    void badRecordingLinesAreRejected() throws Exception {
        Path f = tmp.resolve("bad.jsonl");
        Files.writeString(f, "{\"text\":\"x\"}\n");
        assertThrows(IllegalArgumentException.class, () -> ReplayProvider.load(f, bus, () -> Origin.main(1)));
    }

    @Test
    void recordingRoundTrips() throws Exception {
        Path f = tmp.resolve("rec.jsonl");
        ReplayProvider source = ReplayProvider.ofReplies(List.of("```js\n1\n```", "done"), bus, () -> Origin.main(1));
        RecordingProvider rec = new RecordingProvider(source, f);
        rec.stream(REQ, d -> {});
        rec.stream(REQ, d -> {});
        assertThrows(ModelException.class, () -> rec.stream(REQ, d -> {}));
        ReplayProvider again = ReplayProvider.load(f, bus, () -> Origin.main(1));
        assertEquals("```js\n1\n```", again.stream(REQ, d -> {}).text());
        assertEquals("done", again.stream(REQ, d -> {}).text());
        assertTrue(assertThrows(ModelException.class, () -> again.stream(REQ, d -> {})).getMessage().contains("no more replies"));
    }
}
