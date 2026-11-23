package dev.jharness.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;

import org.graalvm.polyglot.HostAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.config.ConfigException;
import dev.jharness.config.Settings;
import dev.jharness.events.SessionLog;
import dev.jharness.llm.ChatRequest;
import dev.jharness.llm.ReplayProvider;
import dev.jharness.security.Prompter;

/** Spec 025: custom agents from .jharness/agents and ~/.jharness/agents, and the reviewer example. */
class CustomAgentTest {

    @TempDir
    Path tmp;

    private Path agent(Path root, String name, String json, String prompt, String helpers) throws Exception {
        Path d = Files.createDirectories(root.resolve(".jharness/agents").resolve(name));
        Files.writeString(d.resolve("agent.json"), json);
        if (prompt != null) {
            Files.writeString(d.resolve("prompt.md"), prompt);
        }
        if (helpers != null) {
            Files.writeString(d.resolve("helpers.js"), helpers);
        }
        return d;
    }

    @Test
    void foundInTheUserAndTrustedProjectFolders() throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        agent(home, "mine", "{\"description\":\"user agent\"}", null, null);
        agent(home, "both", "{\"description\":\"user version\"}", null, null);
        agent(ws, "both", "{\"description\":\"project version\"}", null, null);
        agent(ws, "proj", "{\"description\":\"project agent\"}", null, null);
        Files.createDirectories(ws.resolve(".jharness/agents/Bad_Name"));
        Files.writeString(ws.resolve(".jharness/agents/Bad_Name/agent.json"), "{}");

        assertEquals("project version", AgentDefinition.resolve(home, ws, true, "both").description());
        assertEquals("user version", AgentDefinition.resolve(home, ws, false, "both").description(), "an untrusted project can't replace it");
        assertEquals("project", AgentDefinition.resolve(home, ws, true, "proj").source());
        var e = assertThrows(ConfigException.class, () -> AgentDefinition.resolve(home, ws, false, "proj"));
        assertTrue(e.getMessage().contains("haven't trusted"), e.getMessage());
        e = assertThrows(ConfigException.class, () -> AgentDefinition.resolve(home, ws, true, "nope"));
        assertTrue(e.getMessage().contains("unknown agent \"nope\"") && e.getMessage().contains("both, mine, proj"), e.getMessage());
        assertThrows(ConfigException.class, () -> AgentDefinition.resolve(home, ws, true, "Bad_Name"));

        List<String> lines = AgentDefinition.describe(home, ws, false, "mine");
        assertEquals("  default  the built-in agent", lines.get(0));
        assertTrue(lines.contains("* mine  (user) user agent"), lines.toString());
        assertTrue(lines.contains("  proj  (project, ignored: the project isn't trusted)"), lines.toString());
    }

    @Test
    void definitionsAreChecked() throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        String[][] bad = {
                {"{}", "\"description\""},
                {"{\"description\":\"x\",\"color\":1}", "unknown key \"color\""},
                {"{\"description\":\"x\",\"tools\":[\"shell\"]}", "not an api method"},
                {"{\"description\":\"x\",\"tools\":\"readFile\"}", "list of api methods"},
                {"{\"description\":\"x\",\"maxSteps\":0}", "\"maxSteps\""},
                {"{\"description\":\"x\",\"maxSteps\":501}", "\"maxSteps\""},
                {"{\"description\":\"x\",\"plan\":\"yes\"}", "\"plan\""},
                {"{\"description\":\"" + "x".repeat(201) + "\"}", "\"description\""},
                {"not json", "agent.json"},
        };
        for (int i = 0; i < bad.length; i++) {
            agent(ws, "a" + i, bad[i][0], null, null);
            int n = i;
            var e = assertThrows(ConfigException.class, () -> AgentDefinition.resolve(home, ws, true, "a" + n), bad[i][0]);
            assertTrue(e.getMessage().contains(bad[i][1]) && e.getMessage().contains(".jharness/agents/a" + i), e.getMessage());
        }
        Path big = agent(ws, "big", "{\"description\":\"x\"}", "p".repeat(AgentDefinition.MAX_FILE_BYTES + 1), null);
        assertTrue(assertThrows(ConfigException.class, () -> AgentDefinition.resolve(home, ws, true, "big")).getMessage().contains("larger than"));
        Path link = agent(ws, "link", "{\"description\":\"x\"}", null, null);
        Files.createSymbolicLink(link.resolve("helpers.js"), big.resolve("prompt.md"));
        assertTrue(assertThrows(ConfigException.class, () -> AgentDefinition.resolve(home, ws, true, "link")).getMessage().contains("not a link"));

        AgentDefinition d = AgentDefinition.read(new AgentDefinition.Found("ok", "project",
                agent(ws, "ok", "{\"description\":\" Reads. \",\"model\":\"m\",\"tools\":[\"readFile\"],\"maxSteps\":5,\"plan\":true}", "Be brief.", "function f() { return 1; }"),
                false));
        assertEquals("Reads.", d.description());
        assertEquals("m", d.model());
        assertEquals(Set.of("readContext", "readFile"), d.tools(), "readContext is always allowed");
        assertEquals(5, d.maxSteps());
        assertTrue(d.plan());
        assertEquals("Be brief.", d.prompt());
    }

    /** The tools list names exactly the host's api methods. */
    @Test
    void apiNamesMatchTheHost() {
        Set<String> host = new TreeSet<>();
        for (var m : dev.jharness.sandbox.HostApi.class.getDeclaredMethods()) {
            if (m.isAnnotationPresent(HostAccess.Export.class)) {
                host.add(m.getName());
            }
        }
        assertEquals(host, new TreeSet<>(AgentDefinition.API));
    }

    private static Path git(Path dir, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("git", "-C", dir.toString(), "-c", "user.name=t", "-c", "user.email=t@t"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor(), String.join(" ", cmd));
        return dir;
    }

    /** The shipped example (spec 025 AC-07): helpers work, tools and plan mode hold, the prompt says so. */
    @Test
    void theReviewerExampleRuns() throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("App.java"), "class App { int add(int a, int b) { return a + b; } }\n");
        git(ws, "init", "-q", "-b", "main");
        git(ws, "add", ".");
        git(ws, "commit", "-q", "-m", "init");
        Files.writeString(ws.resolve("App.java"), "class App { int add(int a, int b) { return a - b; } }\n");
        Path example = Path.of("..", "examples", "agents", "reviewer").toAbsolutePath().normalize();
        Path into = Files.createDirectories(ws.resolve(".jharness/agents/reviewer"));
        for (String f : List.of("agent.json", "prompt.md", "helpers.js")) {
            Files.copy(example.resolve(f), into.resolve(f));
        }
        AgentDefinition reviewer = AgentDefinition.resolve(home, ws, true, "reviewer");
        ReplayProvider[] replay = new ReplayProvider[1];
        Path log;
        try (Session s = Session.open(home, ws, Settings.defaults(),
                Session.Access.prompter(op -> Prompter.Answer.ONCE).trusted(true).agent(reviewer),
                (bus, turns) -> replay[0] = ReplayProvider.ofReplies(List.of(
                        "```js\nchangedFiles().join(',') + ' | ' + diffOf('App.java')\n```",
                        "```js\napi.writeFile('App.java', 'x')\n```",
                        "```js\napi.npm({op: 'test'})\n```",
                        "App.java line 1: add subtracts."), bus, turns))) {
            assertSame(reviewer, s.agent());
            s.loop().turn("review my changes", d -> {});
            log = s.logPath();
        }
        List<ChatRequest> reqs = replay[0].requests();
        String system = reqs.getFirst().system();
        assertTrue(system.contains("# You are the agent \"reviewer\"") && system.contains("Review the changes in the working tree"), system);
        assertTrue(system.contains("You may use only these api methods: git, grep, listFiles, readContext, readFile."), system);
        assertTrue(system.contains("plan mode") && system.contains("function changedFiles()"), system);
        String afterHelpers = reqs.get(1).messages().getLast().content();
        assertTrue(afterHelpers.contains("Result: App.java,") && afterHelpers.contains("-class App { int add(int a, int b) { return a + b; } }")
                && afterHelpers.contains("+class App { int add(int a, int b) { return a - b; } }"), afterHelpers);
        String afterWrite = reqs.get(2).messages().getLast().content();
        assertTrue(afterWrite.contains("agent reviewer can't use api.writeFile"), afterWrite);
        String afterNpm = reqs.get(3).messages().getLast().content();
        assertTrue(afterNpm.contains("can't use api.npm"), afterNpm);
        assertEquals("class App { int add(int a, int b) { return a - b; } }\n", Files.readString(ws.resolve("App.java")));
        List<JsonNode> events = SessionLog.read(log);
        JsonNode started = events.stream().filter(e -> e.get("type").asText().equals("session.started")).findFirst().orElseThrow();
        assertEquals("reviewer", started.get("customAgent").asText());
        assertEquals("project", started.get("agentSource").asText());
    }

    /** plan: true denies changes even for listed tools; maxSteps ends the turn; a broken helpers.js is reported. */
    @Test
    void planModeStepLimitAndBrokenHelpers() throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        agent(home, "planner", "{\"description\":\"plans\",\"plan\":true,\"maxSteps\":2}", null, "function broken( {");
        AgentDefinition planner = AgentDefinition.resolve(home, ws, false, "planner");
        ReplayProvider[] replay = new ReplayProvider[1];
        String stop;
        try (Session s = Session.open(home, ws, Settings.defaults(),
                Session.Access.prompter(op -> Prompter.Answer.ONCE).agent(planner),
                (bus, turns) -> replay[0] = ReplayProvider.ofReplies(List.of(
                        "```js\napi.writeFile('a.txt', 'x')\n```", "```js\n1\n```", "```js\n2\n```"), bus, turns))) {
            stop = s.loop().turn("go", d -> {}).stopReason();
        }
        assertEquals("max_steps", stop);
        String first = replay[0].requests().get(1).messages().getLast().content();
        assertTrue(first.contains("in the agent's helpers.js"), first);
        assertFalse(Files.exists(ws.resolve("a.txt")));
    }
}
