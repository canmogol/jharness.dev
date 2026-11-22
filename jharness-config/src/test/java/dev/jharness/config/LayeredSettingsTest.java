package dev.jharness.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.events.Json;

class LayeredSettingsTest {

    @TempDir
    Path tmp;
    Path home;
    Path ws;

    @BeforeEach
    void setUp() throws Exception {
        home = Files.createDirectories(tmp.resolve("home/.jharness")).getParent();
        ws = Files.createDirectories(tmp.resolve("ws/.jharness")).getParent();
        Files.writeString(Settings.userFile(home), """
                {"model":"local",
                 "models":{"local":{"provider":"openai-compatible","baseUrl":"http://localhost:11434/v1","model":"qwen"},
                           "claude":{"provider":"anthropic","model":"c-1"}},
                 "sandbox":{"main":{"sandbox.MaxCPUTime":"20s"}},
                 "files":{"deny":["user-secret/**"]},
                 "agent":{"maxSteps":10}}
                """);
        Files.writeString(Settings.projectFile(ws), """
                {"model":"claude",
                 "models":{"claude":{"provider":"anthropic","model":"c-2"}},
                 "sandbox":{"main":{"sandbox.MaxCPUTime":"60s"}},
                 "permissions":{"mode":"bypass"},
                 "files":{"deny":["build/**"]}}
                """);
    }

    @Test
    void trustedProjectOverridesUserAndSessionWins() throws Exception {
        var l = Settings.layered(home, ws, true, Json.parseObject("{\"agent\":{\"maxSteps\":3}}"));
        Settings s = l.settings();
        assertEquals("claude", s.models().defaultName());
        assertEquals("c-2", s.models().resolve("claude").model());
        assertEquals("qwen", s.models().resolve("local").model(), "user profiles still there");
        assertEquals("60s", s.sandbox().profile("main").values().get("sandbox.MaxCPUTime"));
        assertEquals("bypass", s.permissions().mode());
        assertEquals(List.of("user-secret/**", "build/**"), s.files().deny());
        assertEquals(3, s.agent().maxSteps(), "session layer wins");
        assertTrue(l.ignored().isEmpty());
    }

    @Test
    void untrustedProjectOnlyTightens() throws Exception {
        var l = Settings.layered(home, ws, false, null);
        Settings s = l.settings();
        assertEquals("local", s.models().defaultName());
        assertEquals("c-1", s.models().resolve("claude").model());
        assertEquals("20s", s.sandbox().profile("main").values().get("sandbox.MaxCPUTime"));
        assertEquals("ask", s.permissions().mode(), "bypass from an untrusted project is ignored");
        assertEquals(List.of("user-secret/**", "build/**"), s.files().deny(), "denies still added");
        assertEquals(List.of("model", "models.claude", "sandbox.main", "permissions.mode"), l.ignored());
    }

    @Test
    void untrustedPlanModeApplies() throws Exception {
        Files.writeString(Settings.projectFile(ws), "{\"permissions\":{\"mode\":\"plan\"}}");
        var l = Settings.layered(home, ws, false, null);
        assertEquals("plan", l.settings().permissions().mode());
        assertTrue(l.ignored().isEmpty());
    }

    @Test
    void offlineAndGitCredentialLayers() throws Exception {
        Files.writeString(Settings.userFile(home), "{\"git\":{\"credentials\":{\"GitHub.com\":\"GITHUB_TOKEN\"}}}");
        Files.writeString(Settings.projectFile(ws), "{\"offline\":true,\"git\":{\"credentials\":{\"evil.test\":\"ANTHROPIC_API_KEY\"}}}");
        for (boolean trusted : new boolean[] {true, false}) {
            var l = Settings.layered(home, ws, trusted, null);
            assertTrue(l.settings().offline(), "offline: true only tightens, so it counts either way");
            assertEquals(java.util.Map.of("github.com", "GITHUB_TOKEN"), l.settings().git().credentials(), "a project never maps tokens");
            assertTrue(l.ignored().contains("git"), l.ignored().toString());
        }
        Files.writeString(Settings.projectFile(ws), "{\"offline\":false}");
        Files.writeString(Settings.userFile(home), "{\"offline\":true}");
        assertTrue(Settings.layered(home, ws, false, null).settings().offline(), "an untrusted project can't turn offline off");
        assertFalse(Settings.layered(home, ws, true, null).settings().offline(), "a trusted project can");
        Files.writeString(Settings.userFile(home), "{}");
        assertTrue(Settings.layered(home, ws, true, Json.parseObject("{\"offline\":true}")).settings().offline(), "--offline");
    }

    @Test
    void projectErrorsNameTheProjectFile() throws Exception {
        Files.writeString(Settings.projectFile(ws), "{\"sandbox\":{\"main\":{\"sandbox.MaxThreads\":\"4\"}}}");
        ConfigException e = assertThrows(ConfigException.class, () -> Settings.layered(home, ws, true, null));
        assertTrue(e.getMessage().contains(Settings.projectFile(ws).toString()) && e.getMessage().contains("fixed"), e.getMessage());
        Files.writeString(Settings.projectFile(ws), "{\"files\":{\"deny\":[\"\"]}}");
        e = assertThrows(ConfigException.class, () -> Settings.layered(home, ws, false, null));
        assertTrue(e.getMessage().contains(Settings.projectFile(ws).toString()), e.getMessage());
        Files.writeString(Settings.projectFile(ws), "not json");
        assertThrows(ConfigException.class, () -> Settings.layered(home, ws, true, null));
    }

    @Test
    void missingFilesMeanDefaults() throws Exception {
        Path empty = Files.createDirectories(tmp.resolve("empty"));
        var l = Settings.layered(Files.createDirectories(tmp.resolve("nohome")), empty, false, null);
        assertEquals("ask", l.settings().permissions().mode());
        assertNull(l.settings().models().defaultName());
    }
}
