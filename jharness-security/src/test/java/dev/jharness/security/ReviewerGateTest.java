package dev.jharness.security;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.events.EventBus;
import dev.jharness.events.Origin;

/** Spec 038, AC-03: the reviewer only tightens; it runs after plan mode and before the mode and the saved rules. */
class ReviewerGateTest {

    @TempDir
    Path tmp;
    final List<Operation> asked = new ArrayList<>();
    final List<String> reviewedFor = new ArrayList<>();

    private PermissionEngine engine(String mode, Prompter.Answer answer, ActionReviewer.Outcome verdict) throws Exception {
        PermissionEngine e = new PermissionEngine(PermissionMode.of(mode), ProjectRules.load(tmp.resolve("home"), Files.createDirectories(tmp.resolve("ws"))),
                op -> {
                    asked.add(op);
                    return answer;
                }, new EventBus("s-rev"), () -> Origin.main(1));
        e.useReviewer((op, agent) -> {
            reviewedFor.add(agent);
            return new ActionReviewer.Verdict(verdict, verdict == ActionReviewer.Outcome.KEEP ? 0.95 : verdict == ActionReviewer.Outcome.ASK ? 0.5 : 0.1,
                    "reviewer says " + verdict);
        });
        return e;
    }

    static final Operation WRITE = PermissionEngineTest.write("a.txt");

    @Test
    void keepLeavesTheDecisionAlone() throws Exception {
        assertTrue(engine("bypass", Prompter.Answer.DENY, ActionReviewer.Outcome.KEEP).decide(WRITE).allowed());
        assertTrue(asked.isEmpty());
        assertFalse(engine("ask", Prompter.Answer.DENY, ActionReviewer.Outcome.KEEP).decide(WRITE).allowed(), "an asked operation is still asked");
        assertEquals(1, asked.size());
    }

    @Test
    void askTurnsAnAllowIntoAQuestionWithTheReason() throws Exception {
        Decision d = engine("bypass", Prompter.Answer.DENY, ActionReviewer.Outcome.ASK).decide(WRITE);
        assertFalse(d.allowed());
        assertEquals(1, asked.size(), "bypass mode asks once the reviewer has doubts");
        ObjectNode review = (ObjectNode) asked.getFirst().detail().get("review");
        assertEquals("reviewer says ASK", review.get("reason").asText());
        assertEquals(0.5, review.get("confidence").asDouble());
        assertNull(WRITE.detail().get("review"), "the caller's operation is not changed");
        asked.clear();
        assertTrue(engine("accept-edits", Prompter.Answer.ONCE, ActionReviewer.Outcome.ASK).decide(WRITE).allowed(), "the user can still say yes");
        assertEquals(1, asked.size());
    }

    @Test
    void askOverridesASavedRule() throws Exception {
        PermissionEngine e = engine("ask", Prompter.Answer.ALWAYS, ActionReviewer.Outcome.KEEP);
        assertTrue(e.decide(WRITE).allowed());
        assertTrue(e.decide(WRITE).allowed());
        assertEquals(1, asked.size(), "the saved rule answers the second time");
        PermissionEngine doubting = new PermissionEngine(PermissionMode.ASK, ProjectRules.load(tmp.resolve("home"), tmp.resolve("ws")), op -> {
            asked.add(op);
            return Prompter.Answer.DENY;
        }, new EventBus("s-rev2"), () -> Origin.main(1));
        doubting.useReviewer((op, agent) -> new ActionReviewer.Verdict(ActionReviewer.Outcome.ASK, 0.4, "unsure"));
        assertFalse(doubting.decide(WRITE).allowed(), "a saved rule doesn't skip the reviewer's question");
        assertEquals(2, asked.size());
    }

    @Test
    void denyRefusesWithoutAsking() throws Exception {
        Decision d = engine("bypass", Prompter.Answer.ONCE, ActionReviewer.Outcome.DENY).decide(WRITE);
        assertFalse(d.allowed());
        assertEquals("reviewer says DENY: write a.txt", d.reason());
        assertTrue(asked.isEmpty());
    }

    @Test
    void readsAndPlanModeAreNotReviewed() throws Exception {
        PermissionEngine e = engine("plan", Prompter.Answer.ONCE, ActionReviewer.Outcome.KEEP);
        assertFalse(e.decide(WRITE).allowed());
        assertTrue(e.decide(PermissionEngineTest.READ).allowed());
        assertTrue(reviewedFor.isEmpty(), "plan mode denies first; reads never change state");
    }

    @Test
    void theReviewerLearnsWhichAgentAsks() throws Exception {
        PermissionEngine e = engine("bypass", Prompter.Answer.ONCE, ActionReviewer.Outcome.KEEP);
        e.decide(WRITE);
        Caller.as("sub-3", () -> e.decide(WRITE));
        assertEquals(List.of("main", "sub-3"), reviewedFor);
        assertEquals("main", Caller.current(), "the mark ends with the call");
    }
}
