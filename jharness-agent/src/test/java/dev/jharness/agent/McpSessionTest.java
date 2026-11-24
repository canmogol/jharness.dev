package dev.jharness.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.config.Settings;
import dev.jharness.events.SessionLog;
import dev.jharness.llm.ReplayProvider;
import dev.jharness.process.Executables;
import dev.jharness.security.Prompter;

/** Spec 037: a real stdio MCP server in the OS sandbox, reached with api.mcp under the permission gate. */
class McpSessionTest {

    @TempDir
    Path tmp;
    ReplayProvider replay;
    final List<String> asked = new CopyOnWriteArrayList<>();

    private List<JsonNode> session(String settingsJson, Prompter.Answer answer, String... replies) throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path ws = Files.createDirectories(tmp.resolve("ws")).toRealPath();
        try (InputStream in = getClass().getResourceAsStream("/fake_mcp_server.py")) {
            Files.write(ws.resolve("fake_mcp_server.py"), in.readAllBytes());
        }
        Files.createDirectories(home.resolve(".jharness"));
        Files.writeString(Settings.userFile(home), settingsJson.replace("SCRIPT", ws.resolve("fake_mcp_server.py").toString()));
        Settings settings = Settings.layered(home, ws, false, null).settings();
        Path log;
        try (Session s = Session.open(home, ws, settings, Session.Access.prompter(op -> {
            asked.add(op.summary());
            return answer;
        }), (bus, turns) -> replay = ReplayProvider.ofReplies(List.of(replies), bus, turns))) {
            s.loop().turn("go", d -> {});
            log = s.logPath();
        }
        return SessionLog.read(log);
    }

    private String resultOf(int request) {
        return replay.requests().get(request).messages().getLast().content();
    }

    private static String js(String code) {
        return "```js\n" + code + "\n```";
    }

    static final String FAKE = "{\"mcp\": {\"servers\": {\"fake\": {\"command\": \"python3\", \"args\": [\"-I\", \"SCRIPT\"], "
            + "\"secretEnv\": {\"FAKE_TOKEN\": \"JH_TEST_FAKE_TOKEN\"}}}}}";

    private static void needPython() {
        assumeTrue(Executables.resolve("python3", Path.of("/nonexistent")).isPresent(), "needs python3");
    }

    @Test
    void listAndCallToolsUnderTheGate() throws Exception {
        needPython();
        String settings = FAKE.replace(", \"secretEnv\": {\"FAKE_TOKEN\": \"JH_TEST_FAKE_TOKEN\"}", "");
        List<JsonNode> events = session(settings, Prompter.Answer.ONCE,
                js("const l = JSON.parse(api.mcp({list: true}));\n"
                        + "const r = JSON.parse(api.mcp({server: 'fake', tool: 'add', input: {a: 2, b: 3}}));\n"
                        + "const out = [l[0].server + ':' + l[0].tools.map(t => t.name).join(','), r.content[0].text];\n"
                        + "for (const q of [{server: 'nope', tool: 'x'}, {server: 'fake', tool: 'nope'}, {server: 'fake'}, {server: 'fake', tool: 'echo', input: 'x'}, {color: 1}]) {\n"
                        + "  try { api.mcp(q); } catch (e) { out.push(e.message); }\n}\nout.join('\\n')"), "done");
        String r = resultOf(1);
        boolean sandbox = events.stream().anyMatch(e -> e.get("type").asText().equals("sandbox.os") && e.get("available").asBoolean());
        assumeTrue(sandbox, "needs the OS sandbox");
        assertTrue(r.contains("fake:echo,add,env\n5"), r);
        assertTrue(r.contains("no MCP server \"nope\" (servers: fake)"), r);
        assertTrue(r.contains("fake has no tool \"nope\" (tools: echo, add, env)"), r);
        assertTrue(r.contains("needs a \"tool\", or {list: true}"), r);
        assertTrue(r.contains("\"input\" must be an object"), r);
        assertTrue(r.contains("does not take \"color\""), r);
        assertEquals(List.of("mcp fake/add {\"a\":2,\"b\":3}"), asked, "listing doesn't ask; the call does, once");
        assertTrue(replay.requests().getFirst().system().contains("# MCP servers") && replay.requests().getFirst().system().contains("add: Adds two numbers."));
        assertTrue(events.stream().anyMatch(e -> e.get("type").asText().equals("mcp.connected") && e.get("tools").asInt() == 3));
        assertTrue(events.stream().anyMatch(e -> e.get("type").asText().equals("mcp.called") && e.get("tool").asText().equals("add")));
        assertTrue(events.stream().anyMatch(e -> e.get("type").asText().equals("process.started") && e.get("executable").asText().equals("python3")
                && e.get("network").asBoolean()), "started in the sandbox through the proxy");
    }

    @Test
    void aSecretReachesTheServerAsItsVariable() throws Exception {
        needPython();
        Path home = Files.createDirectories(tmp.resolve("home/.jharness"));
        Path secrets = Files.writeString(home.resolve("secrets"), "JH_TEST_FAKE_TOKEN=abcdef\n");
        Files.setPosixFilePermissions(secrets, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        List<JsonNode> events = session(FAKE, Prompter.Answer.ONCE,
                js("JSON.parse(api.mcp({server: 'fake', tool: 'env'})).content[0].text"), "done");
        assumeTrue(events.stream().anyMatch(e -> e.get("type").asText().equals("sandbox.os") && e.get("available").asBoolean()), "needs the OS sandbox");
        assertTrue(resultOf(1).contains("token length 6"), resultOf(1));
        assertTrue(events.stream().noneMatch(e -> e.toString().contains("abcdef")), "the secret is never logged");
    }

    @Test
    void aDeniedCallNeverReachesTheServer() throws Exception {
        needPython();
        String settings = FAKE.replace(", \"secretEnv\": {\"FAKE_TOKEN\": \"JH_TEST_FAKE_TOKEN\"}", "");
        List<JsonNode> events = session(settings, Prompter.Answer.DENY,
                js("try { api.mcp({server: 'fake', tool: 'echo', input: {text: 'x'}}) } catch (e) { e.message }"), "done");
        assertTrue(resultOf(1).contains("permission denied"), resultOf(1));
        assertTrue(events.stream().noneMatch(e -> e.get("type").asText().equals("mcp.called")));
    }

    @Test
    void aServerThatCantStartIsListedWithItsError() throws Exception {
        List<JsonNode> events = session("{\"mcp\": {\"servers\": {\"gone\": {\"command\": \"no-such-mcp-server\"}, "
                + "\"secret\": {\"url\": \"https://mcp.example.com/mcp\", \"tokenSecret\": \"JH_TEST_UNSET_SECRET\"}}}}", Prompter.Answer.ONCE,
                js("JSON.parse(api.mcp({list: true})).map(s => s.server + ' ' + s.available + ' ' + s.error).join('\\n')"), "done");
        String r = resultOf(1);
        assertTrue(r.contains("gone false no-such-mcp-server was not found on PATH"), r);
        assertTrue(r.contains("secret false secret JH_TEST_UNSET_SECRET is not set"), r);
        assertTrue(replay.requests().getFirst().system().contains("- gone (not available:"), "the prompt says so");
        assertEquals(2, events.stream().filter(e -> e.get("type").asText().equals("mcp.connected") && e.has("error")).count());
    }

    @Test
    void aProjectCantAddMcpServers() throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home2"));
        Path ws = Files.createDirectories(tmp.resolve("ws2"));
        Files.createDirectories(ws.resolve(".jharness"));
        Files.writeString(Settings.projectFile(ws), "{\"mcp\": {\"servers\": {\"evil\": {\"command\": \"python3\"}}}}");
        Settings.Layered l = Settings.layered(home, ws, true, null);
        assertTrue(l.settings().mcp().servers().isEmpty(), "even a trusted project");
        assertTrue(l.ignored().contains("mcp"));
    }
}
