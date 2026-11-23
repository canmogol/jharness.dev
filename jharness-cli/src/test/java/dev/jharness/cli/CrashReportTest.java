package dev.jharness.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.events.Event;
import dev.jharness.events.Json;

/** Spec 021: crash reports, and exit 3 with {@code session.crashed} when the host fails. */
class CrashReportTest {

    @TempDir
    Path tmp;
    Path home;
    Path ws;
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws Exception {
        home = Files.createDirectories(tmp.resolve("home"));
        ws = Files.createDirectories(tmp.resolve("ws"));
    }

    private Main main() {
        Main m = new Main();
        m.out = new PrintStream(out, true, StandardCharsets.UTF_8);
        m.err = new PrintStream(err, true, StandardCharsets.UTF_8);
        return m;
    }

    private Path replay() throws Exception {
        Path r = tmp.resolve("r.jsonl");
        Files.writeString(r, Json.write(Json.object().put("reply", "Hi.")) + "\n");
        return r;
    }

    private List<Path> reports() throws Exception {
        Path d = CrashReport.dir(home);
        if (!Files.isDirectory(d)) {
            return List.of();
        }
        try (var s = Files.list(d)) {
            return s.sorted().toList();
        }
    }

    @Test
    void reportHoldsTheTraceAndEnvelopesOnly() throws Exception {
        Path log = Files.createDirectories(home.resolve(".jharness/sessions/abc")).resolve("s-1.jsonl");
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < CrashReport.RECENT + 5; i++) {
            lines.append(new Event("e" + i, Instant.parse("2026-11-23T10:00:00Z"), "s-1", "main", 2, "host", "custom.n" + i,
                    Json.object().put("secret", "BODY-CANARY")).toJsonLine()).append('\n');
        }
        Files.writeString(log, lines + "{torn");
        Throwable t = new IllegalStateException("outer", new java.io.IOException("inner at " + home.resolve("x")));
        Path f = CrashReport.write(home, t, Thread.currentThread(), new CrashReport.Context("s-1", log),
                Instant.parse("2026-11-23T10:00:00Z"));
        assertEquals("crash-20261123T100000Z-s-1.txt", f.getFileName().toString());
        String text = Files.readString(f);
        assertTrue(text.startsWith("JHarness crash report\n" + CrashReport.NOTE), text);
        assertTrue(text.contains("version: " + Main.VERSION), text);
        assertTrue(text.contains("session: s-1"), text);
        assertTrue(text.contains("session log: ~/.jharness/sessions/abc/s-1.jsonl"), text);
        assertTrue(text.contains("IllegalStateException: outer") && text.contains("Caused by: java.io.IOException: inner at ~/x"), text);
        assertTrue(text.contains("at dev.jharness.cli.CrashReportTest"), text);
        assertTrue(text.contains("custom.n34") && !text.contains("custom.n4\n"), "the last " + CrashReport.RECENT + " events: " + text);
        assertTrue(text.contains("2026-11-23T10:00:00Z  t2  custom.n34"), text);
        assertFalse(text.contains("BODY-CANARY"), text);
        assertFalse(text.contains(home.toString()), "home shows as ~");
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f)));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f.getParent())));
    }

    @Test
    void keepsTheNewestTwenty() throws Exception {
        Instant t0 = Instant.parse("2026-11-01T00:00:00Z");
        for (int i = 0; i < CrashReport.KEEP + 5; i++) {
            CrashReport.write(home, new RuntimeException("n" + i), null, CrashReport.Context.NONE, t0.plusSeconds(i));
        }
        CrashReport.write(home, new RuntimeException("same second"), null, CrashReport.Context.NONE, t0.plusSeconds(CrashReport.KEEP + 4));
        List<Path> r = reports();
        assertEquals(CrashReport.KEEP, r.size());
        assertFalse(r.stream().anyMatch(p -> Files.isRegularFile(p) && readQuiet(p).contains("RuntimeException: n0\n")));
        assertTrue(r.stream().anyMatch(p -> p.getFileName().toString().endsWith("-1.txt")), "a second report in the same second: " + r);
    }

    private static String readQuiet(Path p) {
        try {
            return Files.readString(p);
        } catch (java.io.IOException e) {
            return "";
        }
    }

    @Test
    void anInternalErrorExitsThreeWithAReportAndAnEvent() throws Exception {
        Main m = main();
        m.afterOpen = s -> {
            throw new IllegalStateException("boom");
        };
        assertEquals(3, Main.run(m, "--home", home.toString(), "--cwd", ws.toString(), "--replay", replay().toString(), "-p", "x"));
        String e = err.toString(StandardCharsets.UTF_8);
        assertTrue(e.contains("jharness: internal error (IllegalStateException: boom); crash report: " + CrashReport.dir(home)), e);
        assertTrue(e.contains("--debug prints the stack trace"), e);
        List<Path> r = reports();
        assertEquals(1, r.size());
        String text = Files.readString(r.getFirst());
        assertTrue(text.contains("IllegalStateException: boom") && text.contains("session: s-"), text);
        assertTrue(text.contains("session.started"), "recent events: " + text);
        String log;
        try (var files = Files.walk(home.resolve(".jharness/sessions"))) {
            log = Files.readString(files.filter(f -> f.toString().endsWith(".jsonl")).findFirst().orElseThrow());
        }
        var crashed = log.lines().map(Json::parse).filter(n -> n.get("type").asText().equals("session.crashed")).toList();
        assertEquals(1, crashed.size(), log);
        assertEquals("java.lang.IllegalStateException", crashed.getFirst().get("exception").asText());
        assertEquals(r.getFirst().toString(), crashed.getFirst().get("report").asText());
    }

    @Test
    void anErrorBeforeASessionAlsoGetsAReport() throws Exception {
        Main m = main();
        m.afterOpen = s -> {
            throw new StackOverflowError();
        };
        assertEquals(3, Main.run(m, "--debug", "--home", home.toString(), "--cwd", ws.toString(), "--replay", replay().toString(), "-p", "x"));
        String e = err.toString(StandardCharsets.UTF_8);
        assertTrue(e.contains("internal error (StackOverflowError)"), e);
        assertTrue(e.contains("at dev.jharness.cli."), "--debug prints the trace: " + e);
        assertEquals(1, reports().size());
    }

    @Test
    void anotherThreadsExceptionWritesAReportAndGoesOn() throws Exception {
        Main m = main();
        m.home = home;
        m.afterOpen = s -> {
            Thread t = new Thread(() -> {}, "proxy-worker");
            m.uncaught(t, new NullPointerException("in a worker"));
        };
        assertEquals(0, Main.run(m, "--home", home.toString(), "--cwd", ws.toString(), "--replay", replay().toString(), "-p", "x"));
        String e = err.toString(StandardCharsets.UTF_8);
        assertTrue(e.contains("internal error in thread proxy-worker (NullPointerException: in a worker); crash report: "), e);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("Hi."), "the turn still ran");
        assertEquals(1, reports().size());
        assertTrue(Files.readString(reports().getFirst()).contains("thread: proxy-worker"));
    }

    @Test
    void settingsAndIoErrorsStayExitTwo() {
        assertEquals(2, Main.run(main(), "--home", home.toString(), "--cwd", ws.toString(), "-p", "x"));
        assertTrue(reports_().isEmpty());
    }

    private List<Path> reports_() {
        try {
            return reports();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
