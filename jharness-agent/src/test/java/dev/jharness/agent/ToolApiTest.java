package dev.jharness.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.config.Settings;
import dev.jharness.events.Json;
import dev.jharness.events.SessionLog;
import dev.jharness.llm.ReplayProvider;
import dev.jharness.security.PermissionGate;

/** Spec 028: {@code api.tool}, {@code api.tools}, the prompt's tools section and {@code tool:NAME} in a custom agent's tools. */
class ToolApiTest {

    @TempDir
    Path tmp;
    ReplayProvider replay;

    private List<JsonNode> session(boolean trusted, AgentDefinition agent, String... replies) throws Exception {
        Path home = tmp.resolve("home");
        Path ws = tmp.resolve("ws");
        Path log;
        try (Session s = Session.open(home, ws, Settings.defaults(), Session.Access.gate(PermissionGate.allowAll()).trusted(trusted).agent(agent),
                (bus, turns) -> replay = ReplayProvider.ofReplies(List.of(replies), bus, turns))) {
            s.loop().turn("go", d -> {});
            log = s.logPath();
        }
        return SessionLog.read(log);
    }

    private Path workspace() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.createDirectories(ws.resolve(".jharness/tools"));
        Files.writeString(ws.resolve(".jharness/tools/gitv.json"), """
                {"description": "git's version", "executable": "git", "proxy": "none",
                 "operations": {"version": {"args": ["--version"]}}}
                """);
        return ws;
    }

    private String resultOf(int request) {
        return replay.requests().get(request).messages().getLast().content();
    }

    @Test
    void toolsListsEveryToolAndThePromptDescribesThem() throws Exception {
        workspace();
        List<JsonNode> events = session(true, null, "```js\napi.tools()\n```", "done");
        JsonNode list = Json.parse(resultOf(1).substring(resultOf(1).indexOf('[')));
        List<String> names = new java.util.ArrayList<>();
        list.forEach(t -> names.add(t.get("name").asText() + ":" + t.get("source").asText()));
        assertEquals(List.of("gitv:project", "gradle:user", "maven:user", "npm:user"), names);
        JsonNode gitv = list.get(0);
        assertTrue(gitv.get("available").asBoolean(), gitv.toString());
        assertEquals("[]", Json.write(gitv.get("operations").get("version")));
        String system = replay.requests().getFirst().system();
        assertTrue(system.contains("# Tools") && system.contains("api.tool(name, {op, ...fields})"), system);
        assertTrue(system.contains("- maven") && system.contains("package (module, skipTests, profiles, threads)"), system);
        assertTrue(system.contains("- gitv: git's version [defined by the project]"), system);
        assertTrue(system.contains("tests (string): Test selectors"), system);
        JsonNode loaded = events.stream().filter(e -> e.get("type").asText().equals("tools.loaded")).findFirst().orElseThrow();
        assertTrue(loaded.get("projectTools").asBoolean());
    }

    @Test
    void untrustedProjectsListToolsAsUnavailableAndIgnoreTheProjects() throws Exception {
        workspace();
        session(false, null, "```js\nJSON.parse(api.tools()).map(t => t.name + ' ' + t.available + ' ' + (t.reason || '')).join('\\n')\n```", "done");
        String r = resultOf(1);
        assertFalse(r.contains("gitv"), r);
        assertTrue(r.contains("maven false this project isn't trusted"), r);
        assertTrue(replay.requests().getFirst().system().contains("- maven (not available: this project isn't trusted)"));
    }

    @Test
    void toolRunsAndRefusesBadCalls() throws Exception {
        workspace();
        List<JsonNode> events = session(true, null, """
                ```js
                const out = [];
                for (const f of [() => api.tool('gitv', 'version'), () => api.tool('gitv', {op: 'version'}), () => api.tool('', {op: 'x'}),
                    () => api.tool('nope', {op: 'x'}), () => api.maven]) {
                  try { const v = f(); out.push(v === undefined ? 'undefined' : JSON.parse(v).exitCode); } catch (e) { out.push(e.message); }
                }
                out.join('\\n')
                ```""", "done");
        String r = resultOf(1);
        boolean sandbox = events.stream().anyMatch(e -> e.get("type").asText().equals("sandbox.os") && e.get("available").asBoolean());
        if (sandbox) {
            assertTrue(r.startsWith("Result of your code:\nResult: 0\n0\n"), r);
            assertTrue(events.stream().anyMatch(e -> e.get("type").asText().equals("process.started") && e.get("executable").asText().equals("git")
                    && !e.get("network").asBoolean()), "proxy none: no network");
        }
        assertTrue(r.contains("api.tool needs a tool name first"), r);
        assertTrue(r.contains("no tool \"nope\" (tools: gitv, gradle, maven, npm"), r);
        assertTrue(r.contains("\nundefined"), "api.maven is gone: " + r);
        assertTrue(events.stream().anyMatch(e -> e.get("type").asText().equals("permission.decided") && e.get("api").asText().equals("tool")
                && e.get("kind").asText().equals("build") && e.get("summary").asText().equals("gitv version (the project's tool)")), "asked as a build");
    }

    @Test
    void anAgentsToolsCanNameOneTool() throws Exception {
        Path ws = workspace();
        // A second runnable tool, so the refusal can't come from a missing executable.
        Files.writeString(ws.resolve(".jharness/tools/gitw.json"), Files.readString(ws.resolve(".jharness/tools/gitv.json")));
        Path a = Files.createDirectories(tmp.resolve("home/.jharness/agents/one"));
        Files.writeString(a.resolve("agent.json"), "{\"description\":\"one tool\",\"tools\":[\"tool:gitv\",\"tools\"]}");
        AgentDefinition one = AgentDefinition.resolve(tmp.resolve("home"), tmp.resolve("ws"), true, "one");
        session(true, one, """
                ```js
                const out = [];
                for (const f of [() => api.tool('gitv', 'version'), () => api.tool('gitw', 'version')]) {
                  try { f(); out.push('ran'); } catch (e) { out.push(e.message); }
                }
                out.join('\\n')
                ```""", "done");
        String[] lines = resultOf(1).substring(resultOf(1).indexOf("Result: ") + 8).split("\n");
        assertFalse(lines[0].contains("can't use"), "tool:gitv allows gitv: " + lines[0]);
        assertTrue(lines[1].contains("agent one can't use api.tool"), "gitw isn't in its tools: " + lines[1]);
        var e = assertThrows(dev.jharness.config.ConfigException.class, () -> {
            Files.writeString(a.resolve("agent.json"), "{\"description\":\"x\",\"tools\":[\"tool:Bad!\"]}");
            AgentDefinition.resolve(tmp.resolve("home"), tmp.resolve("ws"), true, "one");
        });
        assertTrue(e.getMessage().contains("tool:NAME"), e.getMessage());
    }
}
