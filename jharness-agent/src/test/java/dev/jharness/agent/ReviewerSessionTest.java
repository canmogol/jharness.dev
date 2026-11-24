package dev.jharness.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.config.ConfigException;
import dev.jharness.config.Settings;
import dev.jharness.events.Json;
import dev.jharness.events.SessionLog;
import dev.jharness.llm.ReplayProvider;
import dev.jharness.security.Prompter;

/**
 * Spec 038, AC-03 and AC-05: the reviewer in a real session. Its endpoint goes through the egress proxy with no
 * rule, it sees each agent's request, and it can only tighten what the permission mode would do.
 */
class ReviewerSessionTest {

    @TempDir
    Path tmp;
    Path home;
    Path ws;
    HttpServer server;
    volatile double noul = 0.95;
    final List<JsonNode> reviewed = new CopyOnWriteArrayList<>();
    final List<String> asked = new CopyOnWriteArrayList<>();
    ReplayProvider replay;
    List<JsonNode> events;

    @BeforeEach
    void setUp() throws Exception {
        home = Files.createDirectories(tmp.resolve("home"));
        ws = Files.createDirectories(tmp.resolve("ws")).toRealPath();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/systemone", x -> {
            reviewed.add(Json.parse(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] out = ("{\"model\": \"jev-latest\", \"answers\": {\"fits\": {\"type\": \"noul\", \"noul\": " + noul + "}}}")
                    .getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200, out.length);
            x.getResponseBody().write(out);
            x.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String settings(String mode, String extra) {
        return "{\"permissions\": {\"mode\": \"" + mode + "\"}, \"reviewer\": {\"enabled\": true, \"url\": \"http://127.0.0.1:"
                + server.getAddress().getPort() + "/v1/systemone\"" + extra + "}}";
    }

    private void run(String settingsJson, Prompter.Answer answer, String prompt, String... replies) throws Exception {
        Files.createDirectories(home.resolve(".jharness"));
        Files.writeString(Settings.userFile(home), settingsJson);
        Settings settings = Settings.layered(home, ws, false, null).settings();
        Path log;
        try (Session s = Session.open(home, ws, settings, Session.Access.prompter(op -> {
            asked.add(op.summary() + (op.detail().has("review") ? " [review]" : ""));
            return answer;
        }), (bus, turns) -> replay = ReplayProvider.ofReplies(List.of(replies), bus, turns))) {
            assertTrue(s.describeReviewer().startsWith("Action reviewer: jev-latest at http://127.0.0.1:"), s.describeReviewer());
            assertFalse(s.describeReviewer().contains("sent to this server"), "a local reviewer keeps the context here");
            s.loop().turn(prompt, d -> {});
            log = s.logPath();
        }
        events = SessionLog.read(log);
    }

    private static String js(String code) {
        return "```js\n" + code + "\n```";
    }

    private String resultOf(int request) {
        return replay.requests().get(request).messages().getLast().content();
    }

    @Test
    void aConfidentReviewerKeepsBypassMode() throws Exception {
        run(settings("bypass", ""), Prompter.Answer.DENY, "Write hello to a.txt", js("api.writeFile('a.txt', 'hello'); 'ok'"), "done");
        assertEquals("hello", Files.readString(ws.resolve("a.txt")));
        assertTrue(asked.isEmpty());
        assertEquals(1, reviewed.size(), "reached through the proxy without a network rule");
        assertEquals("Write hello to a.txt", reviewed.getFirst().at("/state/userRequest").asText());
        assertEquals("writeFile", reviewed.getFirst().at("/state/action/api").asText());
        assertTrue(events.stream().anyMatch(e -> e.get("type").asText().equals("reviewer.ready")));
        assertTrue(events.stream().anyMatch(e -> e.get("type").asText().equals("review.verdict") && e.get("outcome").asText().equals("keep")));
        assertTrue(events.stream().noneMatch(e -> e.get("type").asText().equals("network.asked")), "the endpoint is allowed automatically");
    }

    @Test
    void anUnsureReviewerAsksEvenInBypassMode() throws Exception {
        noul = 0.5;
        run(settings("bypass", ""), Prompter.Answer.DENY, "Read a.txt", js("try { api.writeFile('a.txt', 'x'); 'written' } catch (e) { e.message }"), "done");
        assertFalse(Files.exists(ws.resolve("a.txt")));
        assertEquals(List.of("write a.txt (1 byte) [review]"), asked.stream().map(a -> a.replace("bytes", "byte")).toList());
    }

    @Test
    void aLowConfidenceDeniesWithoutAsking() throws Exception {
        noul = 0.05;
        run(settings("ask", ""), Prompter.Answer.ONCE, "Read a.txt", js("try { api.writeFile('a.txt', 'x'); 'written' } catch (e) { e.message }"), "done");
        assertFalse(Files.exists(ws.resolve("a.txt")));
        assertTrue(asked.isEmpty());
        assertTrue(resultOf(1).contains("the action reviewer judged this action off-task"), resultOf(1));
    }

    @Test
    void readsAreNeverSent() throws Exception {
        Files.writeString(ws.resolve("a.txt"), "hi");
        run(settings("ask", ""), Prompter.Answer.ONCE, "Read a.txt", js("api.readFile('a.txt')"), "done");
        assertTrue(reviewed.isEmpty());
    }

    @Test
    void aSubagentsActionCarriesItsTask() throws Exception {
        run(settings("bypass", ""), Prompter.Answer.DENY, "Make b.txt",
                js("JSON.parse(api.spawnAgent({task: 'Write b.txt with the word hi'})).reply"),
                js("api.writeFile('b.txt', 'hi'); 'ok'"), "sub done", "done");
        assertEquals("hi", Files.readString(ws.resolve("b.txt")));
        JsonNode write = reviewed.stream().filter(b -> b.at("/state/action/api").asText().equals("writeFile")).findFirst().orElseThrow();
        assertEquals("sub-1", write.at("/state/agent").asText());
        assertEquals("Write b.txt with the word hi", write.at("/state/agentTask").asText());
        assertEquals("Make b.txt", write.at("/state/userRequest").asText());
    }

    @Test
    void aMissingKeyStopsStartUp() throws Exception {
        Files.createDirectories(home.resolve(".jharness"));
        Files.writeString(Settings.userFile(home), settings("ask", ", \"apiKeyEnv\": \"JH_TEST_UNSET_REVIEWER_KEY\""));
        Settings settings = Settings.layered(home, ws, false, null).settings();
        ConfigException e = assertThrows(ConfigException.class, () -> Session.open(home, ws, settings, Session.Access.prompter(op -> Prompter.Answer.ONCE),
                (bus, turns) -> ReplayProvider.ofReplies(List.of("done"), bus, turns)));
        assertTrue(e.getMessage().contains("secret JH_TEST_UNSET_REVIEWER_KEY is not set"), e.getMessage());
    }
}
