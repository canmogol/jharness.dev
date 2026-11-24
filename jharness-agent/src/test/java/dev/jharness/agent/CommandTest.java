package dev.jharness.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.config.Settings;
import dev.jharness.llm.ReplayProvider;
import dev.jharness.security.Prompter;

/** Spec 035: slash commands from Markdown templates and JS files. */
class CommandTest {

    @TempDir
    Path tmp;

    static final Set<String> BUILT_IN = Set.of("/help", "/model", "/undo");

    private Session open(boolean trusted) throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("a.txt"), "hello");
        return Session.open(home, ws, Settings.defaults(), Session.Access.prompter(op -> Prompter.Answer.ONCE).trusted(trusted),
                (bus, turns) -> ReplayProvider.ofReplies(List.of(), bus, turns));
    }

    private Path user() throws Exception {
        return Files.createDirectories(tmp.resolve("home/.jharness/commands"));
    }

    @Test
    void templatesAndScripts() throws Exception {
        Files.writeString(user().resolve("greet.md"), "---\ndescription: say hello\n---\nSay hello to $ARGUMENTS, then stop.\n");
        Files.writeString(user().resolve("plain.md"), "Explain the build.\nMore lines.\n");
        Files.writeString(user().resolve("peek.js"), "const description = 'show a.txt';\nfunction run(args) { return {print: api.readFile('a.txt') + ' ' + args}; }");
        Files.writeString(user().resolve("ask.js"), "function run(args) { return 'Fix ' + args; }");
        Files.writeString(user().resolve("quiet.js"), "function run(args) { }");
        Files.writeString(user().resolve("boom.js"), "function run(args) { throw new Error('nope'); }");
        try (Session s = open(false)) {
            CommandRunner c = s.commands(BUILT_IN);
            assertEquals("Say hello to Bob, then stop.", c.run("greet", "Bob").prompt());
            assertEquals("say hello", c.get("greet").description());
            assertEquals("Explain the build.", c.get("plain").description(), "the first line describes a template without front matter");
            assertEquals("hello src/", c.run("peek", "src/").print());
            assertEquals("show a.txt", c.get("peek").description());
            assertEquals("Fix the login bug", c.run("ask", "the login bug").prompt());
            var none = c.run("quiet", "");
            assertNull(none.prompt());
            assertNull(none.print());
            assertTrue(assertThrows(IllegalStateException.class, () -> c.run("boom", "")).getMessage().contains("nope"));
            assertTrue(c.describe().stream().anyMatch(l -> l.contains("/greet") && l.contains("say hello")));
        }
    }

    @Test
    void badFilesAreReportedAndBuiltInsCantBeReplaced() throws Exception {
        Files.writeString(user().resolve("help.md"), "x");
        Files.writeString(user().resolve("Bad.md"), "x");
        Files.writeString(user().resolve("norun.js"), "const x = 1;");
        Files.writeString(user().resolve("syntax.js"), "function run( {");
        Files.writeString(user().resolve("twice.md"), "a");
        Files.writeString(user().resolve("twice.js"), "function run() { return 'b'; }");
        try (Session s = open(false)) {
            var e = s.commands(BUILT_IN).errors();
            assertFalse(e.containsKey("~/.jharness/commands/help.md"), "a clash, not an error: " + e);
            assertEquals(List.of(new CommandRunner.Clash("~/.jharness/commands/help.md", "help", "user")), s.commands(BUILT_IN).clashes());
            assertTrue(e.get("~/.jharness/commands/Bad.md").contains("command names"), e.toString());
            assertTrue(e.get("~/.jharness/commands/norun.js").contains("no run(args)"), e.toString());
            assertTrue(e.get("~/.jharness/commands/syntax.js").contains("doesn't run"), e.toString());
            assertTrue(e.get("~/.jharness/commands/twice.md").contains("defined twice"), e.toString());
            assertNull(s.commands(BUILT_IN).get("help"));
        }
    }

    /** Spec 041 AC-02, AC-05: aliases are built-in names too; args lists come from front matter or a JS array. */
    @Test
    void aliasesWinAndArgsAreDeclared() throws Exception {
        Files.writeString(user().resolve("q.md"), "quick");
        Files.writeString(user().resolve("quit.js"), "function run() { return 'x'; }");
        Files.writeString(user().resolve("ship.md"), "---\ndescription: ship it\nargs: [patch, minor, \"major\"]\n---\nShip a $ARGUMENTS release.\n");
        Files.writeString(user().resolve("lint.js"), "const args = ['fix', 'check', 'fix'];\nfunction run(args) { return 'Lint: ' + args; }");
        Files.writeString(user().resolve("plain.md"), "No args here.");
        Files.writeString(user().resolve("wide.md"), "---\nargs: two words, ok\n---\nx");
        Path log;
        try (Session s = open(true)) {
            var r = s.commands(java.util.Set.of("/exit", "/quit", "/q"));
            assertEquals(List.of("q", "quit"), r.clashes().stream().map(CommandRunner.Clash::name).toList());
            assertNull(r.get("q"));
            assertEquals(List.of("patch", "minor", "major"), r.get("ship").args());
            assertEquals(List.of("fix", "check"), r.get("lint").args(), "duplicates dropped");
            assertEquals(List.of(), r.get("plain").args());
            assertEquals("Lint: fix", r.run("lint", "fix").prompt(), "run(args) still gets the typed argument");
            assertTrue(r.errors().get("~/.jharness/commands/wide.md").contains("one word"), r.errors().toString());
            log = s.logPath();
        }
        var shadowed = dev.jharness.events.SessionLog.read(log).stream().filter(e -> e.get("type").asText().equals("command.shadowed")).toList();
        assertEquals(2, shadowed.size());
        assertEquals("~/.jharness/commands/q.md", shadowed.getFirst().get("file").asText());
    }

    @Test
    void projectCommandsOnlyInTrustedProjectsAndTheyReplaceTheUsers() throws Exception {
        Files.writeString(user().resolve("deploy.md"), "user deploy");
        Path p = Files.createDirectories(tmp.resolve("ws/.jharness/commands"));
        Files.writeString(p.resolve("deploy.md"), "project deploy");
        try (Session s = open(false)) {
            assertEquals("user deploy", s.commands(BUILT_IN).run("deploy", "").prompt());
        }
        try (Session s = open(true)) {
            assertEquals("project deploy", s.commands(BUILT_IN).run("deploy", "").prompt());
            assertEquals("project", s.commands(BUILT_IN).get("deploy").source());
        }
    }

    /** The shipped examples run. */
    @Test
    void theExampleCommandsWork() throws Exception {
        for (String f : List.of("review.md", "changed.js")) {
            Files.copy(Path.of("..", "examples", ".jharness", "commands", f).toAbsolutePath().normalize(), user().resolve(f));
        }
        try (Session s = open(false)) {
            CommandRunner c = s.commands(BUILT_IN);
            assertTrue(c.run("review", "in src/").prompt().startsWith("Review the uncommitted changes in this project in src/."));
            String changed = c.run("changed", "").print();
            assertTrue(changed.equals("No changes.") || !changed.isEmpty(), changed);
        }
    }
}
