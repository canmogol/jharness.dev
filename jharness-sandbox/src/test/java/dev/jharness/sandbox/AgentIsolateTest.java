package dev.jharness.sandbox;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.config.SandboxSettings;
import dev.jharness.events.Origin;
import dev.jharness.security.DefaultGate;

class AgentIsolateTest {

    @TempDir
    Path tmp;
    Fixture fx;

    @BeforeEach
    void setUp() throws Exception {
        fx = new Fixture(tmp);
    }

    private AgentIsolate isolate(Map<String, String> changes) throws Exception {
        return Fixture.isolate(Fixture.profile(changes), fx.bus);
    }

    @Test
    void valuesOutputAndErrors() throws Exception {
        try (AgentIsolate iso = isolate(Map.of())) {
            var s = fx.services(new DefaultGate());
            assertEquals("2", iso.run("1 + 1", s).value());
            assertEquals("hi", iso.run("'hi'", s).value());
            assertEquals("{\"a\":[1,2]}", iso.run("({a:[1,2]})", s).value());
            assertNull(iso.run("undefined", s).value());
            RunResult out = iso.run("console.log('hello'); 3", s);
            assertEquals("hello\n", out.output());
            RunResult thrown = iso.run("const a = 1;\nconst b = 2;\nthrow new Error('boom');\n", s);
            assertEquals("error", thrown.status());
            assertEquals(3, thrown.line());
            assertTrue(thrown.error().contains("boom"));
            assertTrue(thrown.forModel().contains("Error at line 3"));
            RunResult syntax = iso.run("let x = ;", s);
            assertEquals("error", syntax.status());
            assertEquals(1, syntax.line());
            assertTrue(fx.types().contains("sandbox.run_started"));
            assertTrue(fx.types().contains("sandbox.run_completed"));
        }
    }

    @Test
    void freshContextPerRunOnTheSameEngine() throws Exception {
        try (AgentIsolate iso = isolate(Map.of())) {
            var s = fx.services(new DefaultGate());
            assertEquals("1", iso.run("globalThis.leftover = 1; leftover", s).value());
            assertEquals("undefined", iso.run("typeof leftover", s).value());
        }
    }

    @Test
    void noHostAccessBeyondApi() throws Exception {
        try (AgentIsolate iso = isolate(Map.of())) {
            var s = fx.services(new DefaultGate());
            assertEquals("undefined", iso.run("typeof Java", s).value());
            assertEquals("undefined", iso.run("typeof Polyglot", s).value());
            assertEquals("true", iso.run("Object.isFrozen(api)", s).value());
            assertEquals("error", iso.run("api.readFile = () => 'x'; 'use strict'; (function(){'use strict'; api.x = 1})()", s).status());
            assertEquals("error", iso.run("new Worker('x')", s).status());
        }
    }

    @Test
    void cpuLimitTripsAndTheNextRunWorks() throws Exception {
        try (AgentIsolate iso = isolate(Map.of("sandbox.MaxCPUTime", "1s"))) {
            var s = fx.services(new DefaultGate());
            RunResult r = iso.run("while (true) {}", s);
            assertEquals("limit", r.status(), r.toString());
            assertEquals("sandbox.MaxCPUTime", r.limit());
            assertTrue(fx.types().contains("sandbox.limit_exceeded"));
            assertEquals("ok", iso.run("api.readFile('src/App.java').length > 0", s).status());
        }
    }

    /** Spec 018 RUN-02: the CPU limit trips inside regex backtracking with no message; it is still named. */
    @Test
    void cpuLimitInsideRegexBacktrackingIsNamed() throws Exception {
        try (AgentIsolate iso = isolate(Map.of("sandbox.MaxCPUTime", "1s"))) {
            var s = fx.services(new DefaultGate());
            RunResult r = iso.run("/^(a+)+\\1$/.test('a'.repeat(60) + '!')", s);
            assertEquals("limit", r.status(), r.toString());
            assertEquals("sandbox.MaxCPUTime", r.limit(), r.toString());
            assertTrue(r.error().contains("CPU time"), r.error());
            assertEquals("ok", iso.run("/^(a+)+$/.test('a'.repeat(5000) + '!')", s).status(), "TRegex matches this one in linear time");
        }
    }

    @Test
    void heapLimitTripsAndTheNextRunWorks() throws Exception {
        try (AgentIsolate iso = isolate(Map.of("sandbox.MaxHeapMemory", "64MB", "engine.MaxIsolateMemory", "256MB"))) {
            var s = fx.services(new DefaultGate());
            RunResult r = iso.run("const a = []; while (true) { a.push(new Array(100000).fill(1)); }", s);
            assertEquals("limit", r.status(), r.toString());
            assertEquals("sandbox.MaxHeapMemory", r.limit());
            assertEquals("3", iso.run("1 + 2", s).value());
        }
    }

    @Test
    void outputLimitTrips() throws Exception {
        try (AgentIsolate iso = isolate(Map.of("sandbox.MaxOutputStreamSize", "1KB"))) {
            RunResult r = iso.run("for (let i = 0; i < 1000; i++) console.log('x'.repeat(100));", fx.services(new DefaultGate()));
            assertEquals("limit", r.status(), r.toString());
            assertTrue(r.output().length() <= 2048);
        }
    }

    @Test
    void wallClockCancelsAndTheNextRunWorks() throws Exception {
        try (AgentIsolate iso = isolate(Map.of("wallClock", "1s"))) {
            var s = fx.services(new DefaultGate());
            long t0 = System.nanoTime();
            RunResult r = iso.run("while (true) {}", s);
            assertEquals("cancelled", r.status(), r.toString());
            assertEquals("wallClock", r.limit());
            assertTrue((System.nanoTime() - t0) / 1e9 < 8);
            assertTrue(fx.events.stream().anyMatch(e -> e.type().equals("sandbox.watchdog") && e.body().get("step").asText().equals("context_close")));
            assertFalse(iso.isDead());
            assertEquals("ok", iso.run("1", s).status());
        }
    }

    @Test
    void permissionWaitsDoNotCountTowardTheWallClock() throws Exception {
        try (AgentIsolate iso = isolate(Map.of("wallClock", "1s"))) {
            var s = fx.services(op -> {
                try {
                    Thread.sleep(700);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return dev.jharness.security.Decision.allow("slow user");
            });
            RunResult r = iso.run("api.readFile('src/App.java'); api.readFile('src/App.java'); 'done'", s);
            assertEquals("ok", r.status(), r.toString());
        }
    }

    @Test
    void memoryProtectionSettings() throws Exception {
        IsolateManager m = new IsolateManager(SandboxSettings.defaults(), fx.bus, () -> Origin.main(1));
        try (AgentIsolate off = m.create("main", "main")) {
            assertFalse(off.memoryProtection());
        }
        var auto = dev.jharness.config.Settings.parse("{\"sandbox\":{\"main\":{\"engine.MemoryProtection\":\"auto\"}}}", "t").sandbox();
        IsolateManager ma = new IsolateManager(auto, fx.bus, () -> Origin.main(1));
        boolean available;
        try (AgentIsolate a = ma.create("main", "main")) {
            available = a.memoryProtection();
            assertEquals("2", a.run("1+1", fx.services(new DefaultGate())).value());
        }
        assertTrue(fx.types().contains("sandbox.memory_protection"));
        var on = dev.jharness.config.Settings.parse("{\"sandbox\":{\"main\":{\"engine.MemoryProtection\":\"on\"}}}", "t").sandbox();
        IsolateManager mo = new IsolateManager(on, fx.bus, () -> Origin.main(1));
        if (available) {
            mo.create("main", "main").close();
        } else {
            assertThrows(IllegalStateException.class, () -> mo.create("main", "main"));
        }
    }

    @Test
    void limitNames() {
        assertEquals("sandbox.MaxCPUTime", AgentIsolate.limitName("Maximum CPU time limit of 1000ms exceeded."));
        assertEquals("sandbox.MaxHeapMemory", AgentIsolate.limitName("Maximum heap memory limit of 64MB exceeded."));
        assertEquals("engine.MaxIsolateMemory", AgentIsolate.limitName("Garbage-collected heap size exceeded."));
    }
}
