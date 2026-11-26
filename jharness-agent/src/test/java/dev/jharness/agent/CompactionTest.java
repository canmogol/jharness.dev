package dev.jharness.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.config.Settings;
import dev.jharness.events.Json;
import dev.jharness.events.SessionLog;
import dev.jharness.llm.ChatMessage;
import dev.jharness.llm.ChatRequest;
import dev.jharness.llm.ReplayProvider;
import dev.jharness.security.Prompter;

/**
 * Spec 039: token counting and compaction. The replay claims a 100,000-token window and reports token counts, so a
 * reply that says the request held 80,000 tokens fills the context past 80% and the next model call compacts first.
 */
class CompactionTest {

    @TempDir
    Path tmp;
    ReplayProvider replay;
    List<JsonNode> events;
    final List<String> lines = new ArrayList<>();

    static JsonNode reply(String text) {
        return Json.object().put("reply", text);
    }

    static JsonNode full(String text) {
        return Json.object().put("reply", text).put("inputTokens", 80_000).put("outputTokens", 100);
    }

    static String js(String code) {
        return "```js\n" + code + "\n```";
    }

    interface Script {
        void run(Session s) throws Exception;
    }

    private void session(Settings settings, List<JsonNode> replies, Script script) throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path log;
        try (Session s = Session.open(home, ws, settings, Session.Access.prompter(op -> Prompter.Answer.ONCE),
                (bus, turns) -> replay = new ReplayProvider(replies, bus, turns).contextWindow(100_000))) {
            script.run(s);
            log = s.logPath();
        }
        events = SessionLog.read(log);
    }

    private List<JsonNode> of(String type) {
        return events.stream().filter(e -> e.get("type").asText().equals(type)).toList();
    }

    private static List<String> roles(ChatRequest r) {
        return r.messages().stream().map(ChatMessage::role).toList();
    }

    @Test
    void aFullContextIsSummarizedBeforeTheNextCall() throws Exception {
        session(Settings.defaults(), List.of(full(js("'step one done'")), reply("The user asked to go; step one ran."), reply("done")), s -> {
            var before = s.loop().context();
            assertTrue(before.estimated(), "nothing counted yet");
            assertEquals(100_000, before.window());
            assertEquals(80_000, before.threshold());
            TurnResult r = s.loop().turn("go", d -> {});
            assertEquals("done", r.stopReason());
            assertEquals(3, replay.requests().size(), "the step, the summary, the next step");
            ChatRequest summary = replay.requests().get(1);
            assertEquals(AgentLoop.COMPACT_SYSTEM, summary.system());
            assertEquals(4096, summary.maxTokens(), "spec 066: summaryTokens, with no maxTokens from the replay");
            assertTrue(summary.messages().getFirst().content().contains("Keep the summary under about 3,072 words."));
            assertEquals(1, summary.messages().size());
            String text = summary.messages().getFirst().content();
            assertTrue(text.startsWith(AgentLoop.COMPACT_INSTRUCTIONS.strip()), text);
            assertTrue(text.contains("### User or tool result\ngo") && text.contains("step one done"), text);
            ChatRequest next = replay.requests().get(2);
            assertEquals(List.of("user", "assistant", "user"), roles(next), "the first turn's messages are all in the summary");
            assertEquals(AgentLoop.SUMMARY_PREFIX + "The user asked to go; step one ran.", next.messages().get(0).content());
            assertEquals(AgentLoop.SUMMARY_ACK, next.messages().get(1).content());
            assertTrue(next.messages().get(2).content().startsWith("go\n\n(Your earlier steps in this turn are in the summary above"));
            assertTrue(s.loop().context().tokens() < 10_000, "the context is small again");
        });
        JsonNode c = of("context.compacted").getFirst();
        assertEquals("auto", c.get("by").asText());
        assertTrue(c.get("tokensBefore").asInt() >= 80_000);
        assertTrue(c.get("tokensAfter").asInt() < 10_000);
        assertEquals("The user asked to go; step one ran.", c.get("summary").asText());
        JsonNode done = of("turn.completed").getLast();
        assertEquals(100_000, done.get("contextWindow").asInt());
        assertTrue(done.has("contextTokens"));
    }

    @Test
    void aLaterTurnKeepsItsOwnMessagesAndUndoKeepsTheSummary() throws Exception {
        session(Settings.defaults(), List.of(reply("hello"), full(js("'checked'")), reply("Summary: said hello, then checked."), reply("done")), s -> {
            s.loop().turn("say hello", d -> {});
            s.loop().turn("check it", d -> {});
            ChatRequest next = replay.requests().get(3);
            assertEquals(List.of("user", "assistant", "user", "assistant", "user"), roles(next));
            assertTrue(next.messages().get(2).content().endsWith("check it"));
            assertTrue(next.messages().get(4).content().contains("checked"));
            String summarized = replay.requests().get(2).messages().getFirst().content();
            assertTrue(summarized.contains("say hello") && !summarized.contains("check it"), "only the older turn is summarized");
            assertEquals(List.of(2), s.loop().turns());
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> s.loop().rewind(0, false));
            assertTrue(e.getMessage().contains("turns up to 1 were compacted"), e.getMessage());
            s.loop().undo(false);
            assertEquals(List.of("user", "assistant"), s.loop().conversation().stream().map(ChatMessage::role).toList(),
                    "undoing the turn keeps the summary of the turns before it");
        });
    }

    @Test
    void compactBetweenTurns() throws Exception {
        session(Settings.defaults(), List.of(reply("hi"), reply("They said hi."), reply("still here")), s -> {
            s.loop().turn("hi", d -> {});
            String msg = s.loop().compact();
            assertTrue(msg.startsWith("Compacted the conversation: about "), msg);
            assertEquals(List.of("user", "assistant"), s.loop().conversation().stream().map(ChatMessage::role).toList());
            s.loop().turn("are you there?", d -> {});
            assertEquals(List.of("user", "assistant", "user"), roles(replay.requests().get(2)));
            assertTrue(s.loop().turns().equals(List.of(2)));
        });
        assertEquals("user", of("context.compacted").getFirst().get("by").asText());
    }

    @Test
    void aFailedSummaryLeavesTheConversationAndIsNotRetriedEveryStep() throws Exception {
        session(Settings.defaults(), List.of(full(js("1")), Json.object().put("error", "model overloaded"), full(js("2")), reply("done")), s -> {
            TurnResult r = s.loop().turn("go", d -> {});
            assertEquals("done", r.stopReason());
            assertEquals(4, replay.requests().size(), "one summary attempt, then the turn goes on without one");
            assertEquals(3, replay.requests().get(2).messages().size(), "the conversation is unchanged");
        });
        assertEquals("model overloaded", of("context.compact_failed").getFirst().get("error").asText());
        assertTrue(of("context.compacted").isEmpty());
    }

    @Test
    void autoCompactCanBeTurnedOff() throws Exception {
        Settings off = Settings.parse("{\"agent\": {\"autoCompact\": false}}", "t");
        session(off, List.of(full(js("1")), reply("done")), s -> {
            assertEquals("done", s.loop().turn("go", d -> {}).stopReason());
            assertEquals(2, replay.requests().size());
            var c = s.loop().context();
            assertFalse(c.estimated(), "the model's own count is used");
            assertTrue(c.tokens() >= 80_100, String.valueOf(c.tokens()));
        });
    }

    static JsonNode thoughtOnly() {
        return Json.object().put("reply", "").put("thinking", "Let me think about what matters here...").put("stopReason", "length")
                .put("outputTokens", 4096);
    }

    /** Spec 066: a thinking model that runs out of tokens before writing gets one retry with twice the room. */
    @Test
    void aThinkingModelThatRunsOutGetsOneRetryWithMoreRoom() throws Exception {
        session(Settings.defaults(), List.of(full(js("'step one done'")), thoughtOnly(), reply("Step one ran."), reply("done")), s -> {
            s.loop().turn("go", d -> {});
            assertEquals(4, replay.requests().size(), "the step, two summary requests, the next step");
            assertEquals(4096, replay.requests().get(1).maxTokens());
            assertEquals(8192, replay.requests().get(2).maxTokens(), "twice the room");
            assertEquals(AgentLoop.SUMMARY_PREFIX + "Step one ran.", replay.requests().get(3).messages().getFirst().content());
        });
        assertEquals(1, of("context.compacted").size());
        assertTrue(of("context.compact_failed").isEmpty());
    }

    /** Spec 066: when the retry runs out too, the error says why, and the event carries the stop reason and thinking. */
    @Test
    void aSummaryThatNeverComesSaysWhy() throws Exception {
        session(Settings.defaults(), List.of(full(js("'step one done'")), thoughtOnly(), thoughtOnly(), reply("done")), s -> {
            s.loop().turn("go", d -> {});
        });
        JsonNode f = of("context.compact_failed").getFirst();
        assertTrue(f.get("error").asText().startsWith("the model used all 8,192 tokens thinking and wrote no summary"), f.toString());
        assertEquals("length", f.get("stopReason").asText());
        assertEquals(2, f.get("attempts").asInt());
        assertTrue(f.get("thinkingChars").asInt() > 0);
    }

    /** Spec 066: an empty reply that didn't run out isn't retried. */
    @Test
    void anEmptySummaryThatDidNotRunOutIsNotRetried() throws Exception {
        session(Settings.defaults(), List.of(full(js("'step one done'")), reply(""), reply("done")), s -> {
            s.loop().turn("go", d -> {});
            assertEquals(3, replay.requests().size());
        });
        JsonNode f = of("context.compact_failed").getFirst();
        assertEquals("the summary was empty (stop reason: replay)", f.get("error").asText());
        assertEquals(1, f.get("attempts").asInt());
    }

    /** Spec 066: the profile's summaryTokens sets the request; the transcript leaves room for it. */
    @Test
    void summaryTokensSetsTheRequest() throws Exception {
        session(Settings.defaults(), List.of(full(js("'step one done'")), reply("Ran."), reply("done")), s -> {
            replay.summaryTokens(6000);
            s.loop().turn("go", d -> {});
            ChatRequest summary = replay.requests().get(1);
            assertEquals(6000, summary.maxTokens());
            assertTrue(summary.messages().getFirst().content().contains("about 4,500 words"));
        });
    }
}
