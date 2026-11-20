package dev.jharness.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsTest {

    @TempDir
    Path home;

    private static ConfigException bad(String json) {
        return assertThrows(ConfigException.class, () -> Settings.parse(json, "settings.json"), json);
    }

    @Test
    void missingFileMeansDefaults() throws Exception {
        Settings s = Settings.load(home);
        var main = s.sandbox().profile("main");
        assertEquals("512MB", main.values().get("engine.MaxIsolateMemory"));
        assertEquals("256MB", main.values().get("sandbox.MaxHeapMemory"));
        assertEquals("30s", main.values().get("sandbox.MaxCPUTime"));
        assertEquals("256KB", main.values().get("sandbox.MaxOutputStreamSize"));
        assertEquals(Duration.ofMinutes(15), main.wallClock());
        assertEquals(SandboxSettings.MemoryProtection.OFF, main.memoryProtection());
        assertEquals("256MB", s.sandbox().profile("subagent").values().get("engine.MaxIsolateMemory"));
        assertEquals(Duration.ofMinutes(2), s.timeouts().process());
        assertEquals(30_000, s.timeouts().outputChars());
    }

    @Test
    void loadsUserFile() throws Exception {
        Files.createDirectories(home.resolve(".jharness"));
        Files.writeString(Settings.userFile(home), """
                {"model":"ollama-qwen",
                 "models":{"ollama-qwen":{"provider":"openai-compatible","baseUrl":"http://localhost:11434/v1/","model":"qwen2.5-coder:7b"},
                           "claude":{"provider":"anthropic","model":"claude-x"}},
                 "sandbox":{"main":{"sandbox.MaxCPUTime":"5s","engine.MemoryProtection":"auto"}},
                 "timeouts":{"process":"30s","outputChars":1000}}
                """);
        Settings s = Settings.load(home);
        assertEquals("5s", s.sandbox().profile("main").values().get("sandbox.MaxCPUTime"));
        assertEquals(SandboxSettings.MemoryProtection.AUTO, s.sandbox().profile("main").memoryProtection());
        assertEquals("30s", s.sandbox().profile("subagent").values().get("sandbox.MaxCPUTime"));
        assertEquals(Duration.ofSeconds(30), s.timeouts().process());
        assertEquals(1000, s.timeouts().outputChars());
        ModelProfile def = s.models().resolve(null);
        assertEquals("ollama-qwen", def.name());
        assertEquals("http://localhost:11434/v1", def.baseUrl());
        ModelProfile claude = s.models().resolve("claude");
        assertEquals("https://api.anthropic.com", claude.baseUrl());
        assertEquals("ANTHROPIC_API_KEY", claude.apiKeyEnv());
    }

    @Test
    void unknownKeysAndBadValuesNameTheKey() {
        assertTrue(bad("{\"theme\":\"dark\"}").getMessage().contains("theme"));
        assertTrue(bad("{\"sandbox\":{\"main\":{\"sandbox.MaxCPUTime\":\"0s\"}}}").getMessage().contains("sandbox.main.sandbox.MaxCPUTime"));
        assertTrue(bad("{\"sandbox\":{\"main\":{\"sandbox.MaxCPUTime\":\"30\"}}}").getMessage().contains("MaxCPUTime"));
        assertTrue(bad("{\"sandbox\":{\"main\":{\"bogus\":\"1\"}}}").getMessage().contains("bogus"));
        assertTrue(bad("{\"sandbox\":{\"other\":{}}}").getMessage().contains("other"));
        assertTrue(bad("{\"sandbox\":{\"main\":{\"engine.MemoryProtection\":\"true\"}}}").getMessage().contains("off, auto or on"));
        assertTrue(bad("{\"sandbox\":{\"main\":{\"sandbox.MaxHeapMemory\":\"1GB\"}}}").getMessage().contains("must be below"));
        assertTrue(bad("{\"timeouts\":{\"outputChars\":200000}}").getMessage().contains("outputChars"));
        assertTrue(bad("{\"timeouts\":{\"process\":\"20m\"}}").getMessage().contains("processMax"));
        assertTrue(bad("{\"timeouts\":{\"later\":\"1s\"}}").getMessage().contains("later"));
        assertTrue(bad("not json").getMessage().contains("settings.json"));
    }

    @Test
    void fixedSandboxSettingsAreRejected() {
        for (String k : new String[] {"sandbox.MaxThreads", "engine.IsolateMode", "sandboxPolicy", "hostAccess", "engine.SpawnIsolate"}) {
            ConfigException e = bad("{\"sandbox\":{\"main\":{\"" + k + "\":\"2\"}}}");
            assertTrue(e.getMessage().contains("fixed"), e.getMessage());
        }
    }

    @Test
    void secretsInSettingsAreRejected() {
        for (String k : new String[] {"apiKey", "api_key", "token", "secret", "password", "x-api-key"}) {
            ConfigException e = bad("{\"models\":{\"c\":{\"provider\":\"anthropic\",\"model\":\"m\",\"" + k + "\":\"sk-123\"}}}");
            assertTrue(e.getMessage().contains("apiKeyEnv"), e.getMessage());
        }
        assertTrue(bad("{\"models\":{\"c\":{\"provider\":\"anthropic\",\"model\":\"m\",\"apiKeyEnv\":\"sk-ant-123 456\"}}}")
                .getMessage().contains("environment variable"));
    }

    @Test
    void profileChecks() {
        bad("{\"models\":{\"o\":{\"provider\":\"openai-compatible\",\"model\":\"m\"}}}");
        bad("{\"models\":{\"o\":{\"provider\":\"modeljars\",\"model\":\"m\"}}}");
        bad("{\"models\":{\"o\":{\"provider\":\"openai-compatible\",\"baseUrl\":\"file:///etc\",\"model\":\"m\"}}}");
        bad("{\"model\":\"missing\",\"models\":{}}");
    }

    @Test
    void resolveUnknownListsKnownNames() throws Exception {
        Settings s = Settings.parse("{\"models\":{\"a\":{\"provider\":\"anthropic\",\"model\":\"m\"},"
                + "\"b\":{\"provider\":\"openai-compatible\",\"baseUrl\":\"http://h:1/v1\",\"model\":\"m\"}}}", "s");
        ConfigException e = assertThrows(ConfigException.class, () -> s.models().resolve("c"));
        assertTrue(e.getMessage().contains("a, b"), e.getMessage());
        assertThrows(ConfigException.class, () -> s.models().resolve(null));
        assertEquals("b", s.models().resolve("b").name());
    }

    @Test
    void agentSection() throws Exception {
        assertEquals(100, Settings.defaults().agent().maxSteps());
        assertEquals(500, Settings.parse("{\"agent\":{\"maxSteps\":500}}", "s").agent().maxSteps());
        assertEquals(5, Settings.parse("{\"agent\":{\"maxSteps\":5}}", "s").agent().maxSteps());
        assertTrue(bad("{\"agent\":{\"maxSteps\":0}}").getMessage().contains("agent.maxSteps"));
        assertTrue(bad("{\"agent\":{\"maxSteps\":501}}").getMessage().contains("1 to 500"));
        assertTrue(bad("{\"agent\":{\"steps\":3}}").getMessage().contains("agent.steps"));
    }

    @Test
    void uiSection() throws Exception {
        assertEquals("steps", Settings.defaults().ui().events());
        assertEquals("all", Settings.parse("{\"ui\":{\"events\":\"all\"}}", "s").ui().events());
        assertEquals("none", Settings.parse("{\"ui\":{\"events\":\"none\"}}", "s").ui().events());
        assertTrue(bad("{\"ui\":{\"events\":\"some\"}}").getMessage().contains("ui.events"));
        assertTrue(bad("{\"ui\":{\"events\":1}}").getMessage().contains("ui.events"));
        assertTrue(bad("{\"ui\":{\"theme\":\"dark\"}}").getMessage().contains("ui.theme"));
        assertTrue(bad("{\"ui\":true}").getMessage().contains("\"ui\""));
        assertEquals("hide", Settings.defaults().ui().thinking());
        assertEquals("show", Settings.parse("{\"ui\":{\"thinking\":\"show\"}}", "s").ui().thinking());
        assertTrue(bad("{\"ui\":{\"thinking\":\"yes\"}}").getMessage().contains("ui.thinking"));
    }

    @Test
    void permissionsAndFiles(@TempDir Path docs) throws Exception {
        assertEquals("ask", Settings.defaults().permissions().mode());
        assertEquals("plan", Settings.parse("{\"permissions\":{\"mode\":\"plan\"}}", "s").permissions().mode());
        assertTrue(bad("{\"permissions\":{\"mode\":\"yolo\"}}").getMessage().contains("permissions.mode"));
        assertTrue(bad("{\"permissions\":{\"level\":\"ask\"}}").getMessage().contains("permissions.level"));
        Settings f = Settings.parse("{\"files\":{\"mounts\":[\"" + docs + "\"],\"deny\":[\"secrets/**\"]}}", "s");
        assertEquals(java.util.List.of(docs.toRealPath()), f.files().mounts());
        assertEquals(java.util.List.of("secrets/**"), f.files().deny());
        assertTrue(bad("{\"files\":{\"mounts\":[\"relative/docs\"]}}").getMessage().contains("absolute"));
        assertTrue(bad("{\"files\":{\"mounts\":[\"" + docs.resolve("missing") + "\"]}}").getMessage().contains("not a folder"));
        assertTrue(bad("{\"files\":{\"deny\":[\"\"]}}").getMessage().contains("non-empty"));
        assertTrue(bad("{\"files\":{\"allow\":[\"x\"]}}").getMessage().contains("files.allow"));
    }

    @Test
    void profileWithValidates() throws Exception {
        var main = SandboxSettings.defaults().profile("main");
        assertEquals("2s", main.with(Map.of("sandbox.MaxCPUTime", "2s")).values().get("sandbox.MaxCPUTime"));
        assertThrows(ConfigException.class, () -> main.with(Map.of("sandbox.MaxThreads", "4")));
    }
}
