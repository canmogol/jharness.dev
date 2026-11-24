package dev.jharness.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.config.Settings;
import dev.jharness.events.SessionLog;
import dev.jharness.llm.ReplayProvider;
import dev.jharness.security.Prompter;

/**
 * Spec 029: every example in {@code examples.md} runs, as the model would send it, in a real session
 * against a small test project, so an example can't go stale when the api changes.
 */
class ExamplesTest {

    @TempDir
    Path tmp;

    static List<String> blocks() throws IOException {
        String md;
        try (InputStream in = SystemPrompt.class.getResourceAsStream("examples.md")) {
            md = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("```js\\n(.*?)\\n```", Pattern.DOTALL).matcher(md);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** Small enough for a prompt, and enough to teach the patterns small models get wrong. */
    @Test
    void thereAreElevenShortExamplesUsingOnlyRealApiMethods() throws IOException {
        List<String> b = blocks();
        assertEquals(11, b.size());
        for (String code : b) {
            assertTrue(code.lines().count() <= 12, "short: " + code);
            Matcher m = Pattern.compile("\\bapi\\.([A-Za-z]+)\\(").matcher(code);
            while (m.find()) {
                assertTrue(AgentDefinition.API.contains(m.group(1)), "api." + m.group(1) + " doesn't exist: " + code);
            }
        }
        assertTrue(new TreeSet<>(List.of(String.join("\n", b).split("\\W+"))).containsAll(
                List.of("listFiles", "grep", "editFile", "tools", "tool", "git", "fetch", "spawnAgent", "waitAgent", "emit", "writeFile")));
    }

    @Test
    void everyExampleRunsAgainstATestProject() throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path ws = Files.createDirectories(tmp.resolve("project")).toRealPath();
        Files.writeString(ws.resolve("README.md"), "# Answer\n\nPrints the answer.\n");
        Files.createDirectories(ws.resolve("src"));
        Files.writeString(ws.resolve("src/App.java"), "class App {\n    int answer() {\n        return 41;\n    }\n    // TODO: say why\n}\n");
        Files.writeString(ws.resolve("docs.md"), "# Docs\n");
        git(ws, "init", "-q");
        git(ws, "config", "user.email", "test@example.com");
        git(ws, "config", "user.name", "Test");
        git(ws, "add", ".");
        git(ws, "commit", "-q", "-m", "init");
        // The test project's Maven is a stand-in, echo, so the tool examples run anywhere: it prints its arguments.
        Path tools = Files.createDirectories(home.resolve(".jharness/tools"));
        Files.writeString(tools.resolve("maven.json"), """
                {"description": "test stand-in", "executable": "echo", "proxy": "none",
                 "operations": {"test": {"args": ["tested", "{tests}"]}},
                 "fields": {"tests": {"type": "string", "pattern": "[A-Za-z]+"}}}
                """);
        HttpServer web = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        web.createContext("/", x -> {
            byte[] page = "<html><head><title>Config guide</title></head><body><p>Set timeout to 30 s.</p><p>Other.</p></body></html>"
                    .getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", "text/html");
            x.sendResponseHeaders(200, page.length);
            x.getResponseBody().write(page);
            x.close();
        });
        web.start();
        try {
            String url = "http://127.0.0.1:" + web.getAddress().getPort();
            Files.writeString(home.resolve(".jharness/network.rules"), "allow 127.0.0.1:" + web.getAddress().getPort() + "\n");
            List<String> examples = new ArrayList<>();
            for (String b : blocks()) {
                examples.add(b.replace("https://docs.example.com", url));
            }
            run(home, ws, examples);
        } finally {
            web.stop(0);
        }
    }

    private void run(Path home, Path ws, List<String> examples) throws Exception {
        List<String> replies = new ArrayList<>();
        for (String code : examples) {
            replies.add("```js\n" + code + "\n```");
            if (code.contains("background: true")) {
                replies.add("TODO at src/App.java line 5.");
            }
            replies.add("done");
        }
        ReplayProvider[] replay = new ReplayProvider[1];
        Prompter user = op -> op.summary().contains("notes.txt") ? Prompter.Answer.DENY : Prompter.Answer.ONCE;
        Path log;
        try (Session s = Session.open(home, ws, Settings.layered(home, ws, true, null).settings(), Session.Access.prompter(user).trusted(true),
                (bus, turns) -> replay[0] = ReplayProvider.ofReplies(replies, bus, turns))) {
            for (int i = 0; i < examples.size(); i++) {
                TurnResult r = s.loop().turn("example " + (i + 1), d -> {});
                assertEquals("done", r.stopReason(), "example " + (i + 1));
            }
            log = s.logPath();
        }
        List<JsonNode> events = SessionLog.read(log);
        boolean sandbox = events.stream().anyMatch(e -> e.get("type").asText().equals("sandbox.os") && e.get("available").asBoolean());
        List<JsonNode> results = events.stream().filter(e -> e.get("type").asText().equals("loop.result") && e.get("agent").asText().equals("main")).toList();
        assertEquals(examples.size(), results.size());
        List<String> answers = new ArrayList<>();
        for (var req : replay[0].requests()) {
            String last = req.messages().getLast().content();
            if (last.startsWith(AgentLoop.RESULT_PREFIX) && req.system().contains("# Examples")) {
                answers.add(last);
            }
        }
        for (int i = 0; i < examples.size(); i++) {
            String code = examples.get(i);
            boolean usesTool = code.contains("api.tool(");
            if (usesTool && !sandbox) {
                continue;
            }
            assertEquals("ok", results.get(i).get("status").asText(), "example " + (i + 1) + " failed: " + results.get(i) + "\n" + code);
        }
        String all = String.join("\n----\n", answers);
        assertTrue(all.contains("Java files: src/App.java") && all.contains("Prints the answer."), all);
        assertTrue(all.contains("int answer()"), "grep and read: " + all);
        assertTrue(all.contains("+        return 42;"), "edit and diff: " + all);
        if (sandbox) {
            assertTrue(all.contains("maven: all tests pass"), "tools and tool: " + all);
            assertTrue(all.contains("exit 0\ntested AppTest"), "one test class: " + all);
        }
        assertTrue(all.contains("changed files:\n M src/App.java"), "status: " + all);
        assertTrue(all.contains("committed"), "commit: " + all);
        assertTrue(all.contains("Config guide\nSet timeout to 30 s."), "fetch: " + all);
        assertTrue(all.contains("sub-1 (done): TODO at src/App.java line 5."), "background subagent: " + all);
        assertTrue(all.contains("2 Markdown files to check"), "emit: " + all);
        assertTrue(events.stream().anyMatch(e -> e.get("type").asText().equals("agent.progress") && e.get("source").asText().equals("guest")));
        assertTrue(all.contains("could not write: "), "refused write: " + all);
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertTrue(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0, "git " + String.join(" ", args));
    }
}
