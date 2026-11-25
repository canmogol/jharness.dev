package dev.jharness.llm;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ModelEventsTest {

    @Test
    void textAndThinkingAreCapped() {
        String big = "x".repeat(ModelEvents.MAX_CHARS + 10);
        var b = ModelEvents.completed("p", "m", new ChatReply(big, big + "yy", "stop", 1, 2));
        assertEquals(ModelEvents.MAX_CHARS, b.get("text").asText().length());
        assertEquals(10, b.get("textCut").asInt());
        assertEquals(12, b.get("thinkingCut").asInt());
        assertEquals("stop", b.get("stopReason").asText());
        assertEquals(2, b.get("outputTokens").asInt());
    }

    @Test
    void smallTextIsWhole() {
        var b = ModelEvents.completed("p", "m", new ChatReply("hi", "", "stop", 0, 0));
        assertEquals("hi", b.get("text").asText());
        assertFalse(b.has("textCut") || b.has("thinking") || b.has("thinkingCut"));
    }

    /** Spec 049, AC-02: a priced profile's requests carry their cost. */
    @Test
    void costIsAddedWithAPrice() {
        var reply = new ChatReply("hi", "", "stop", 10_000, 2_000, 6_000, 1_000);
        assertFalse(ModelEvents.completed("p", "m", reply).has("costUsd"), "no price, no cost");
        var b = ModelEvents.completed("p", "m", reply, new dev.jharness.config.ModelPrice(3, 15, 0.3, 3.75));
        assertEquals(0.04455, b.get("costUsd").asDouble(), 1e-9);
    }
}
