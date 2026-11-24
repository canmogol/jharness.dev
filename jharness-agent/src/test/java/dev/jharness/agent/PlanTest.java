package dev.jharness.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.config.Settings;
import dev.jharness.events.SessionLog;
import dev.jharness.llm.ReplayProvider;
import dev.jharness.security.PermissionGate;
import dev.jharness.security.Prompter;

/** Spec 036: todo lists and plan mode. */
class PlanTest {

    @TempDir
    Path tmp;
    Path ws;
    ReplayProvider replay;
    final AtomicReference<Prompter.Answer> planAnswer = new AtomicReference<>(Prompter.Answer.ONCE);

    private Session open(AgentDefinition agent, String... replies) throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home"));
        ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("a.txt"), "old");
        return Session.open(home, ws, Settings.defaults(), Session.Access.prompter(op -> op.api().equals("proposePlan") ? planAnswer.get()
                : Prompter.Answer.ONCE).agent(agent), (bus, turns) -> replay = ReplayProvider.ofReplies(List.of(replies), bus, turns));
    }

    private static String js(String code) {
        return "```js\n" + code + "\n```";
    }

    private String resultOf(int request) {
        return replay.requests().get(request).messages().getLast().content();
    }

    @Test
    void todoLists() throws Exception {
        Path log;
        try (Session s = open(null, js("api.todos([{text: 'read', status: 'done'}, {text: 'fix', status: 'in_progress'}, 'test']);\n"
                + "const out = [JSON.parse(api.todos()).map(t => t.status + ':' + t.text).join(',')];\n"
                + "for (const bad of [[{text: ''}], [{text: 'x', status: 'later'}], 'nope', Array(51).fill('x')]) {\n"
                + "  try { api.todos(bad); } catch (e) { out.push(e.message); }\n}\nout.join('\\n')"),
                js("JSON.parse(api.spawnAgent('list')).reply"), js("api.todos(['sub item']); JSON.parse(api.todos()).length"), "one", "done")) {
            s.loop().turn("go", d -> {});
            String r = resultOf(1);
            assertTrue(r.contains("done:read,in_progress:fix,pending:test"), r);
            assertTrue(r.contains("each todo needs a text") && r.contains("pending, in_progress or done") && r.contains("takes a list")
                    && r.contains("at most 50"), r);
            s.loop().turn("sub", d -> {});
            assertEquals(3, s.todos("main").size(), "each agent has its own list");
            assertEquals("sub item", s.todos("sub-1").getFirst()[1]);
            log = s.logPath();
        }
        List<JsonNode> ev = SessionLog.read(log).stream().filter(e -> e.get("type").asText().equals("todo.updated")).toList();
        assertEquals(List.of("main", "sub-1"), ev.stream().map(e -> e.get("agent").asText()).toList());
    }

    @Test
    void planModeRefusesChangesAndAnApprovedPlanLeavesIt() throws Exception {
        try (Session s = open(null,
                js("try { api.writeFile('a.txt', 'x'); 'written' } catch (e) { e.message }"),
                js("JSON.parse(api.proposePlan('1. Change a.txt\\n2. Run the tests')).approved"),
                js("api.writeFile('a.txt', 'new'); 'written'"), "Done.")) {
            assertFalse(s.planMode());
            assertTrue(s.togglePlanMode());
            s.loop().turn("change a.txt", d -> {});
            assertTrue(replay.requests().getFirst().messages().getLast().content().startsWith(PlanState.NOTE), "the note leads the message");
            assertTrue(resultOf(1).contains("plan mode is read-only"), resultOf(1));
            assertTrue(resultOf(2).contains("Result: true"), resultOf(2));
            assertFalse(s.planMode(), "an approved plan leaves plan mode");
            assertTrue(resultOf(3).contains("written"), resultOf(3));
            assertEquals("new", Files.readString(ws.resolve("a.txt")));
        }
    }

    @Test
    void aRejectedPlanKeepsPlanModeAndProposingOutsideIsRefused() throws Exception {
        planAnswer.set(Prompter.Answer.DENY);
        try (Session s = open(null, js("JSON.parse(api.proposePlan('plan')).approved"), "ok",
                js("try { api.proposePlan('x') } catch (e) { e.message }"), "ok")) {
            s.togglePlanMode();
            s.loop().turn("plan it", d -> {});
            assertTrue(resultOf(1).contains("Result: false"), resultOf(1));
            assertTrue(s.planMode());
            assertFalse(s.togglePlanMode(), "/plan turns it off");
            s.loop().turn("now", d -> {});
            assertTrue(resultOf(3).contains("plan mode is off; carry out the work"), resultOf(3));
        }
    }

    @Test
    void aPlanAgentIsLockedInPlanMode() throws Exception {
        Path a = Files.createDirectories(tmp.resolve("home/.jharness/agents/planner"));
        Files.writeString(a.resolve("agent.json"), "{\"description\":\"plans\",\"plan\":true}");
        AgentDefinition planner = AgentDefinition.resolve(tmp.resolve("home"), Files.createDirectories(tmp.resolve("ws")), false, "planner");
        try (Session s = open(planner, js("try { api.proposePlan('x') } catch (e) { e.message }"), "ok")) {
            assertTrue(s.planMode());
            assertTrue(assertThrows(IllegalStateException.class, s::togglePlanMode).getMessage().contains("always runs in plan mode"));
            s.loop().turn("go", d -> {});
            assertTrue(resultOf(1).contains("always runs in plan mode"), resultOf(1));
        }
        try (Session s = Session.open(tmp.resolve("home"), tmp.resolve("ws"), Settings.defaults(), Session.Access.gate(PermissionGate.allowAll()),
                (bus, turns) -> ReplayProvider.ofReplies(List.of(), bus, turns))) {
            assertThrows(IllegalStateException.class, s::togglePlanMode, "a fixed gate has no mode");
        }
    }
}
