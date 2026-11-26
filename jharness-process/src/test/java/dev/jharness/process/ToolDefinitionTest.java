package dev.jharness.process;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.config.Timeouts;
import dev.jharness.events.EventBus;
import dev.jharness.events.Json;
import dev.jharness.events.Origin;
import dev.jharness.process.ToolDefinition.DefinitionException;
import dev.jharness.process.ToolDefinition.Source;
import dev.jharness.security.Workspace;

/** Spec 028: the definition format, its checks, and loading the user's and the project's folders. */
class ToolDefinitionTest {

    @TempDir
    Path tmp;
    Path home;
    Path root;

    static final String GO = """
            {
              "description": "Go",
              "executable": "go",
              "proxy": "env",
              "env": {"GOFLAGS": "-mod=readonly", "GOTOOLCHAIN": "local"},
              "operations": {
                "build": {"args": ["build", "{race}", "{packages}"]},
                "test": {"args": ["test", "{race}", "{count}", "{level}", "{run}", "{packages}"]},
                "vet": {"args": ["vet", "{packages}"]}
              },
              "fields": {
                "packages": {"type": "string", "pattern": "\\\\./[A-Za-z0-9_./-]*", "default": "./..."},
                "race": {"type": "flag", "args": ["-race"]},
                "count": {"type": "integer", "min": 1, "max": 10, "args": ["-count={}"]},
                "level": {"type": "enum", "values": {"short": ["-short"], "full": []}},
                "run": {"type": "string", "pattern": "[A-Za-z0-9_|^$]{1,100}", "args": ["-run", "{}"]}
              }
            }
            """;

    @BeforeEach
    void setUp() throws Exception {
        home = Files.createDirectories(tmp.resolve("home"));
        root = Files.createDirectories(tmp.resolve("ws")).toRealPath();
    }

    private static ToolDefinition parse(String text, Source source) throws DefinitionException {
        return ToolDefinition.parse("go", text, source, "go.json", "h");
    }

    private static String error(String text, Source source) {
        return assertThrows(DefinitionException.class, () -> parse(text, source)).getMessage();
    }

    private static String error(String text) {
        return error(text, Source.USER);
    }

    private BuildOperations ops(ToolDefinition d) {
        var runner = new ProcessRunner(new EventBus("s-t"), () -> Origin.main(1), 30_000, TestSandboxes.in(root));
        return new BuildOperations(runner, new Workspace(root), Timeouts.defaults(), true, ToolDefinitions.of(Map.of(d.name(), d)),
                Map.of(d.name(), new Executable("go", Path.of("/usr/local/go/bin/go"))), home, null, false);
    }

    @Test
    void aNewToolBuildsItsArgvFromTypedFields() throws Exception {
        BuildOperations ops = ops(parse(GO, Source.USER));
        assertEquals(List.of("build", "./..."), ops.args("go", Json.parseObject("{\"op\":\"build\"}")), "the default");
        assertEquals(List.of("test", "-race", "-count=3", "-short", "-run", "TestA|TestB", "./pkg/..."),
                ops.args("go", Json.parseObject("{\"op\":\"test\",\"race\":true,\"count\":3,\"level\":\"short\",\"run\":\"TestA|TestB\",\"packages\":\"./pkg/...\"}")));
        assertEquals(List.of("test", "./..."), ops.args("go", Json.parseObject("{\"op\":\"test\",\"level\":\"full\"}")));
        assertEquals("go test · race, count 3", ops.summary("go", Json.parseObject("{\"op\":\"test\",\"race\":true,\"count\":3}")));
        for (String[] bad : new String[][] {
                {"{\"op\":\"test\",\"count\":11}", "from 1 to 10"},
                {"{\"op\":\"test\",\"count\":\"3\"}", "whole number"},
                {"{\"op\":\"test\",\"level\":\"medium\"}", "must be one of short, full"},
                {"{\"op\":\"test\",\"run\":\"-exec=sh\"}", "can't start with '-'"},
                {"{\"op\":\"test\",\"run\":\"a b\"}", "characters"},
                {"{\"op\":\"vet\",\"race\":true}", "does not take \"race\""},
                {"{\"op\":\"run\"}", "must be one of build, test, vet"},
                {"{\"op\":\"build\",\"packages\":\"/etc\"}", "characters"}}) {
            var e = assertThrows(OperationException.class, () -> ops.check("go", Json.parseObject(bad[0])), bad[0]);
            assertTrue(e.getMessage().contains(bad[1]), bad[0] + ": " + e.getMessage());
        }
        var env = ops.env("go", ops.executable("go"), new ChildSandbox.ProxyRoute(4321, tmp.resolve("p.sock"), "u", "p"), tmp);
        assertEquals("http://u:p@127.0.0.1:4321", env.get("HTTPS_PROXY"));
        assertEquals("localhost,127.0.0.1", env.get("NO_PROXY"));
        assertEquals("-mod=readonly", env.get("GOFLAGS"));
        assertDoesNotThrow(() -> ProcessRunner.checkEnv(env));
        assertTrue(ops.caches("go").isEmpty());
        assertTrue(BuildOperations.grants(ops.definition("go"), List.of()).network());
    }

    @Test
    void definitionsAreCheckedWhenLoaded() {
        assertTrue(error("{\"executable\":\"sh\",\"proxy\":\"none\",\"operations\":{\"x\":{\"args\":[\"-c\",\"id\"]}}}").contains("shells and command runners"));
        assertTrue(error("{\"executable\":\"/bin/go\",\"proxy\":\"none\",\"operations\":{\"x\":{\"args\":[\"x\"]}}}").contains("no path"));
        assertTrue(error("{\"executable\":\"go\",\"proxy\":\"vpn\",\"operations\":{\"x\":{\"args\":[\"x\"]}}}").contains("\"proxy\" must be one of"));
        assertTrue(error("{\"executable\":\"go\",\"proxy\":\"none\",\"operations\":{}}").contains("at least one operation"));
        assertTrue(error("{\"executable\":\"go\",\"proxy\":\"none\",\"cmd\":\"x\",\"operations\":{\"x\":{\"args\":[\"x\"]}}}").contains("unknown key \"cmd\""));
        assertTrue(error("{\"executable\":\"go\",\"proxy\":\"none\",\"operations\":{\"x\":{\"args\":[\"{nope}\"]}}}").contains("{nope} names no field"));
        assertTrue(error("{\"executable\":\"go\",\"proxy\":\"none\",\"operations\":{\"x\":{\"args\":[\"x\"]}},"
                + "\"fields\":{\"a\":{\"type\":\"flag\",\"args\":[\"-a\"]}}}").contains("used by no operation"));
        assertTrue(error("{\"executable\":\"go\",\"proxy\":\"none\",\"operations\":{\"x\":{\"args\":[\"{a}\"]}},"
                + "\"fields\":{\"a\":{\"type\":\"string\"}}}").contains("needs a \"pattern\""));
        assertTrue(error("{\"executable\":\"go\",\"proxy\":\"none\",\"operations\":{\"x\":{\"args\":[\"{a}\"]}},"
                + "\"fields\":{\"a\":{\"type\":\"string\",\"pattern\":\"(\"}}}").contains("not a valid regular expression"));
        assertTrue(error("{\"executable\":\"go\",\"proxy\":\"none\",\"operations\":{\"x\":{\"args\":[\"--x={a}\"]}},"
                + "\"fields\":{\"a\":{\"type\":\"string\",\"pattern\":\"a\",\"args\":[\"-a\",\"{}\"]}}}").contains("exactly one argument"));
        assertTrue(error("{\"executable\":\"go\",\"proxy\":\"none\",\"env\":{\"LD_PRELOAD\":\"/x.so\"},\"operations\":{\"x\":{\"args\":[\"x\"]}}}")
                .contains("LD_PRELOAD can't be set"));
        assertTrue(error("{\"executable\":\"go\",\"proxy\":\"none\",\"env\":{\"HTTPS_PROXY\":\"http://evil\"},\"operations\":{\"x\":{\"args\":[\"x\"]}}}")
                .contains("HTTPS_PROXY can't be set"));
        assertTrue(error("{\"executable\":\"go\",\"proxy\":\"none\",\"writable\":[\"~/.ssh\"],\"operations\":{\"x\":{\"args\":[\"x\"]}}}")
                .contains("can't be made writable"));
        assertTrue(error("{\"executable\":\"go\",\"proxy\":\"none\",\"writable\":[\"/etc\"],\"operations\":{\"x\":{\"args\":[\"x\"]}}}")
                .contains("folders in your home"));
        assertTrue(error("{\"executable\":\"go\",\"proxy\":\"none\",\"writable\":[\"~/../etc\"],\"operations\":{\"x\":{\"args\":[\"x\"]}}}")
                .contains("below your home"));
        assertTrue(error("{\"executable\":\"go\",\"proxy\":\"env\",\"args\":[\"{proxySettings}\"],\"operations\":{\"x\":{\"args\":[\"x\"]}}}")
                .contains("needs \"proxy\": \"maven-settings\""));
        assertTrue(error("{\"executable\":\"go\",\"proxy\":\"none\",\"loopback\":\"macos\",\"operations\":{\"x\":{\"args\":[\"x\"]}}}")
                .contains("needs network"));
        assertTrue(error("{\"executable\":\"go\",\"proxy\":\"none\",\"operations\":{\"x\":{\"args\":[\"{a}\"]}},"
                + "\"fields\":{\"a\":{\"type\":\"integer\",\"min\":1,\"max\":3,\"default\":9,\"args\":[\"{}\"]}}}").contains(".default"));
        assertThrows(DefinitionException.class, () -> ToolDefinition.parse("Go!", GO, Source.USER, "x", "h"));
    }

    /** D-066: a project's tool can't name a workspace wrapper, make home folders writable or open loopback. */
    @Test
    void projectDefinitionsCantReachFurther() throws Exception {
        assertNotNull(parse(GO, Source.PROJECT));
        String base = "{\"executable\":\"go\",\"proxy\":\"env\",\"operations\":{\"x\":{\"args\":[\"x\"]}},";
        assertTrue(error(base + "\"wrapper\":{\"file\":\"gow\"}}", Source.PROJECT).contains("can't set \"wrapper\""));
        assertTrue(error(base + "\"writable\":[\"~/go/pkg/mod\"]}", Source.PROJECT).contains("can't set \"writable\""));
        assertTrue(error(base + "\"loopback\":\"macos\"}", Source.PROJECT).contains("can't set \"loopback\""));
        assertNotNull(parse(base + "\"wrapper\":{\"file\":\"gow\"},\"writable\":[\"~/go/pkg/mod\"],\"loopback\":\"macos\"}", Source.USER));
    }

    @Test
    void theShippedDefaultsAreSeededOnceAndThenTheFolderIsTheUsers() throws Exception {
        List<Path> written = ToolDefinitions.seed(home);
        assertEquals(5, written.size(), "go, gofmt, gradle, maven and npm");
        Path dir = ToolDefinitions.userFolder(home);
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("maven.json"))));
        assertEquals(ToolDefinitions.defaultText("maven"), Files.readString(dir.resolve("maven.json")));
        Files.delete(dir.resolve("npm.json"));
        Files.writeString(dir.resolve("maven.json"), Files.readString(dir.resolve("maven.json")).replace("\"install\":", "\"x-install\":"));
        assertTrue(ToolDefinitions.seed(home).isEmpty(), "never seeded again");
        ToolDefinitions t = ToolDefinitions.load(home, root, false);
        assertEquals(List.of("go", "gofmt", "gradle", "maven"), List.copyOf(t.tools().keySet()), "a deleted file removes the tool");
        assertTrue(t.get("maven").operations().containsKey("x-install"));
        assertTrue(ToolDefinitions.differsFromDefault(home, "maven"));
        assertFalse(ToolDefinitions.differsFromDefault(home, "gradle"));
        ToolDefinitions.reset(home, "maven");
        assertFalse(ToolDefinitions.differsFromDefault(home, "maven"));
    }

    @Test
    void projectToolsAddReplaceAndRemoveOnlyWhenUsed() throws Exception {
        ToolDefinitions.seed(home);
        Path pd = Files.createDirectories(ToolDefinitions.projectFolder(root));
        Files.writeString(pd.resolve("go.json"), GO);
        Files.writeString(pd.resolve("npm.json"), "{\"disabled\": true}");
        Files.writeString(pd.resolve("gradle.json"), "{\"executable\":\"gradle\",\"proxy\":\"java-options\",\"operations\":{\"build\":{\"args\":[\"build\"]}}}");
        Files.writeString(pd.resolve("broken.json"), "{\"executable\":\"bash\",\"proxy\":\"none\",\"operations\":{\"x\":{\"args\":[\"x\"]}}}");
        ToolDefinitions off = ToolDefinitions.load(home, root, false);
        assertEquals(List.of("go", "gofmt", "gradle", "maven", "npm"), List.copyOf(off.tools().keySet()), "unused: only the user's");
        assertTrue(off.project().isEmpty());
        ToolDefinitions on = ToolDefinitions.load(home, root, true);
        assertEquals(List.of("go", "gofmt", "gradle", "maven"), List.copyOf(on.tools().keySet()));
        assertEquals(ToolDefinition.Source.PROJECT, on.get("gradle").source());
        assertEquals(List.of("build"), List.copyOf(on.get("gradle").operations().keySet()));
        assertTrue(on.errors().get("broken").contains("shells and command runners"), on.errors().toString());
        List<String> lines = ToolDefinitions.describeProject(home, root).stream().map(ToolDefinitions.ProjectTool::line).toList();
        assertTrue(lines.contains("go: runs go (build, test, vet), replaces yours"), lines.toString());
        assertTrue(lines.contains("gradle: runs gradle (build), replaces yours"), lines.toString());
        assertTrue(lines.contains("npm: turns off your npm tool"), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("broken: ") && l.contains("bash")), lines.toString());

        Map<String, String> hashes = ToolDefinitions.projectHashes(root);
        assertEquals(List.of("broken", "go", "gradle", "npm"), List.copyOf(hashes.keySet()));
        assertTrue(ToolDefinitions.changed(hashes, hashes).isEmpty());
        Files.writeString(pd.resolve("go.json"), GO.replace("\"vet\"", "\"lint\""));
        assertEquals(List.of("go"), ToolDefinitions.changed(ToolDefinitions.projectHashes(root), hashes));
    }

    @Test
    void filesOthersCanWriteAreRefused() throws Exception {
        ToolDefinitions.seed(home);
        Path f = ToolDefinitions.userFolder(home).resolve("maven.json");
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-rw-rw-"));
        ToolDefinitions t = ToolDefinitions.load(home, root, false);
        assertNull(t.get("maven"));
        assertTrue(t.errors().get("maven").contains("others can write it"), t.errors().toString());
    }

    @Test
    void theShippedDefinitionsDescribeThemselves() {
        var maven = ToolDefinitions.defaults().get("maven").describe();
        assertEquals("user", maven.get("source").asText());
        assertEquals("[\"module\",\"skipTests\",\"profiles\",\"threads\"]", Json.write(maven.get("operations").get("package")));
        assertEquals("folder", maven.get("fields").get("module").get("type").asText());
        assertEquals("[[\"tests\",\"skipTests\"]]", Json.write(maven.get("conflicts")));
        var npm = ToolDefinitions.defaults().get("npm").describe();
        assertTrue(npm.get("fields").get("script").get("required").asBoolean());
    }

    /** Spec 059: an env value that starts with ~/ must stay below the home. */
    @Test
    void homePathsInEnvStayBelowTheHome() {
        for (String v : List.of("~/", "~/../etc", "~/a/../../b", "~//x")) {
            String json = "{\"executable\": \"x\", \"proxy\": \"none\", \"env\": {\"XCACHE\": " + Json.MAPPER.valueToTree(v) + "}, "
                    + "\"operations\": {\"run\": {\"args\": [\"run\"]}}}";
            var e = assertThrows(ToolDefinition.DefinitionException.class, () -> ToolDefinition.parse("x", json, ToolDefinition.Source.USER, "x.json", "h"), v);
            assertTrue(e.getMessage().contains("below your home"), e.getMessage());
        }
        assertDoesNotThrow(() -> ToolDefinition.parse("x", "{\"executable\": \"x\", \"proxy\": \"none\", \"env\": {\"XCACHE\": \"~/.cache/x\"}, "
                + "\"operations\": {\"run\": {\"args\": [\"run\"]}}}", ToolDefinition.Source.USER, "x.json", "h"));
    }
}
