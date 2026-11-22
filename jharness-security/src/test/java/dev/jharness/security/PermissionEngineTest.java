package dev.jharness.security;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.events.Event;
import dev.jharness.events.EventBus;
import dev.jharness.events.Json;
import dev.jharness.events.Origin;

class PermissionEngineTest {

    @TempDir
    Path tmp;
    Path home;
    Path ws;
    final EventBus bus = new EventBus("s-perm");
    final List<Event> events = new ArrayList<>();
    final List<String> asked = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        home = tmp.resolve("home");
        ws = Files.createDirectories(tmp.resolve("ws"));
        bus.subscribe(events::add);
    }

    static Operation write(String path) {
        return new Operation("writeFile", Operation.Kind.WRITE, "write " + path, Json.object().put("path", path));
    }

    static Operation git(String op) {
        return new Operation("git", op.equals("diff") ? Operation.Kind.GIT_READ : Operation.Kind.GIT_WRITE, "git " + op, Json.object().put("op", op));
    }

    static final Operation READ = new Operation("readFile", Operation.Kind.READ, "read a", Json.object().put("path", "a"));
    static final Operation EMIT = new Operation("emit", Operation.Kind.EMIT, "emit", Json.object());

    private PermissionEngine engine(String mode, Prompter.Answer answer) {
        return new PermissionEngine(PermissionMode.of(mode), ProjectRules.load(home, ws), op -> {
            asked.add(op.summary());
            return answer;
        }, bus, () -> Origin.main(1));
    }

    @Test
    void modesFollowTheD041Table() {
        // mode -> [write, git commit] with a prompter that denies: true = allowed without asking
        record Row(String mode, boolean write, boolean commit, int asks) {}
        for (Row r : List.of(new Row("ask", false, false, 2), new Row("accept-edits", true, false, 1), new Row("plan", false, false, 0),
                new Row("bypass", true, true, 0))) {
            asked.clear();
            PermissionEngine e = engine(r.mode(), Prompter.Answer.DENY);
            assertTrue(e.decide(READ).allowed(), r.mode());
            assertTrue(e.decide(git("diff")).allowed(), r.mode());
            assertTrue(e.decide(EMIT).allowed(), r.mode());
            assertEquals(r.write(), e.decide(write("a.txt")).allowed(), r.mode() + " write");
            assertEquals(r.commit(), e.decide(git("commit")).allowed(), r.mode() + " commit");
            assertEquals(r.asks(), asked.size(), r.mode() + " asks");
        }
        assertTrue(engine("plan", Prompter.Answer.ONCE).decide(write("a")).reason().contains("plan mode"));
    }

    @Test
    void askOnceAndDeny() {
        PermissionEngine once = engine("ask", Prompter.Answer.ONCE);
        assertTrue(once.decide(write("a.txt")).allowed());
        assertTrue(once.decide(write("a.txt")).allowed());
        assertEquals(2, asked.size(), "once means once");
        Decision d = engine("ask", Prompter.Answer.DENY).decide(git("commit"));
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("denied by the user"));
        assertTrue(events.stream().anyMatch(e -> e.type().equals("permission.asked")));
        Decision headless = new PermissionEngine(PermissionMode.ASK, ProjectRules.load(home, ws), Prompter.none(), bus, () -> Origin.main(1))
                .decide(write("x"));
        assertFalse(headless.allowed());
    }

    @Test
    void alwaysSavesARuleUsedLaterAndInNewSessions() throws Exception {
        PermissionEngine e = engine("ask", Prompter.Answer.ALWAYS);
        assertTrue(e.decide(write("src/App.java")).allowed());
        assertTrue(e.decide(git("commit")).allowed());
        assertEquals(2, asked.size());
        assertTrue(e.decide(write("src/App.java")).allowed());
        assertTrue(e.decide(git("commit")).allowed());
        assertEquals(2, asked.size(), "saved rules don't ask again");
        assertEquals(2, events.stream().filter(x -> x.type().equals("permission.rule_added")).count());

        Path f = ProjectRules.fileFor(home, ws);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f)));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f.getParent())));
        assertTrue(Files.readString(f).contains("\"path\":\"src/App.java\""));

        PermissionEngine later = engine("ask", Prompter.Answer.DENY);
        assertTrue(later.decide(write("src/App.java")).allowed(), "rule from an earlier session");
        assertFalse(later.decide(write("src/Other.java")).allowed(), "other paths still ask");
        assertFalse(later.decide(git("restore")).allowed(), "other git ops still ask");
    }

    static Operation http(String method, String host) {
        boolean read = method.equals("GET") || method.equals("HEAD");
        return new Operation("http", read ? Operation.Kind.HTTP : Operation.Kind.HTTP_WRITE, method + " https://" + host + "/x",
                Json.object().put("method", method).put("host", host).put("op", method + " " + host));
    }

    @Test
    void httpReadsNeedOnlyTheNetworkRulesAndWritesAsk() {
        for (String mode : List.of("ask", "accept-edits", "plan", "bypass")) {
            asked.clear();
            PermissionEngine e = engine(mode, Prompter.Answer.DENY);
            assertTrue(e.decide(http("GET", "docs.example.com")).allowed(), mode);
            assertTrue(e.decide(http("HEAD", "docs.example.com")).allowed(), mode);
            assertEquals(mode.equals("bypass"), e.decide(http("POST", "api.example.com")).allowed(), mode);
            assertEquals(mode.equals("ask") || mode.equals("accept-edits") ? 1 : 0, asked.size(), mode + " asks");
        }
        assertTrue(engine("plan", Prompter.Answer.ONCE).decide(http("DELETE", "api.example.com")).reason().contains("plan mode"));
    }

    @Test
    void alwaysSavesAnHttpRulePerMethodAndHost() throws Exception {
        assertTrue(engine("ask", Prompter.Answer.ALWAYS).decide(http("POST", "api.example.com")).allowed());
        assertTrue(Files.readString(ProjectRules.fileFor(home, ws)).contains("{\"kind\":\"http-write\",\"op\":\"POST api.example.com\"}"));
        PermissionEngine later = engine("ask", Prompter.Answer.DENY);
        assertTrue(later.decide(http("POST", "api.example.com")).allowed(), "saved rule");
        assertFalse(later.decide(http("PUT", "api.example.com")).allowed(), "other methods still ask");
        assertFalse(later.decide(http("POST", "other.example.com")).allowed(), "other hosts still ask");
    }

    @Test
    void repositoryFilesCantAddRules() throws Exception {
        Files.createDirectories(ws.resolve(".jharness"));
        Files.writeString(ws.resolve(".jharness/permissions.json"), "{\"allow\":[{\"kind\":\"write\",\"path\":\"a.txt\"}]}");
        assertFalse(engine("ask", Prompter.Answer.DENY).decide(write("a.txt")).allowed());
    }

    @Test
    void badRuleFileIsAnError() throws Exception {
        Path f = ProjectRules.fileFor(home, ws);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "{\"allow\":[{\"kind\":\"everything\"}]}");
        assertThrows(IllegalArgumentException.class, () -> ProjectRules.load(home, ws));
    }
}
