package dev.jharness.security;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import dev.jharness.events.Json;

class DefaultGateTest {

    private static Operation op(Operation.Kind k) {
        return new Operation("x", k, "do x", Json.object());
    }

    @Test
    void allowsReadsAndDeniesStateChanges() {
        DefaultGate g = new DefaultGate();
        assertTrue(g.decide(op(Operation.Kind.READ)).allowed());
        assertTrue(g.decide(op(Operation.Kind.GIT_READ)).allowed());
        assertTrue(g.decide(op(Operation.Kind.EMIT)).allowed());
        Decision w = g.decide(op(Operation.Kind.WRITE));
        assertFalse(w.allowed());
        assertTrue(w.reason().contains("needs approval"));
        assertFalse(g.decide(op(Operation.Kind.GIT_WRITE)).allowed());
    }
}
