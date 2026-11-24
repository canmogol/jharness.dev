package dev.jharness.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Spec 038, AC-01: the reviewer section, its defaults and checks, and that only the user's settings set it. */
class ReviewerSettingsTest {

    @TempDir
    Path tmp;

    @Test
    void offByDefault() throws Exception {
        ReviewerSettings r = Settings.parse("{}", "t").reviewer();
        assertFalse(r.enabled());
        assertEquals("jev-latest", r.model());
        assertEquals(0.90, r.askBelow());
        assertEquals(0.20, r.denyBelow());
        assertEquals(Duration.ofSeconds(3), r.timeout());
        assertEquals("ask", r.onError());
    }

    @Test
    void readsEveryKey() throws Exception {
        ReviewerSettings r = Settings.parse("{\"reviewer\": {\"enabled\": true, \"url\": \"https://inference.do-ai.run/v1/systemone\", "
                + "\"model\": \"typesafe-jev-1.13.0\", \"apiKeyEnv\": \"DO_KEY\", \"askBelow\": 0.8, \"denyBelow\": 0.1, \"timeoutMs\": 5000, "
                + "\"onError\": \"deny\"}}", "t").reviewer();
        assertTrue(r.enabled());
        assertEquals("https://inference.do-ai.run/v1/systemone", r.url());
        assertEquals("typesafe-jev-1.13.0", r.model());
        assertEquals("DO_KEY", r.apiKeyEnv());
        assertEquals(0.8, r.askBelow());
        assertEquals(0.1, r.denyBelow());
        assertEquals(Duration.ofMillis(5000), r.timeout());
        assertEquals("deny", r.onError());
    }

    @Test
    void refusesBadValues() {
        String[][] cases = {
                {"{\"enabled\": true}", "reviewer.url is needed"},
                {"{\"url\": \"ftp://x/v1/systemone\"}", "http or https URL"},
                {"{\"color\": 1}", "reviewer.color is not a known setting"},
                {"{\"askBelow\": 1.5}", "askBelow must be a number from 0 to 1"},
                {"{\"askBelow\": 0.3, \"denyBelow\": 0.5}", "must not be above askBelow"},
                {"{\"timeoutMs\": 10}", "timeoutMs must be a whole number from 500 to 60000"},
                {"{\"onError\": \"allow\"}", "a failing reviewer never allows"},
                {"{\"apiKeyEnv\": \"not a name\"}", "name of a secret"},
                {"{\"enabled\": \"yes\"}", "enabled must be true or false"},
        };
        for (String[] c : cases) {
            ConfigException e = assertThrows(ConfigException.class, () -> Settings.parse("{\"reviewer\": " + c[0] + "}", "t"), c[0]);
            assertTrue(e.getMessage().contains(c[1]), c[0] + " -> " + e.getMessage());
        }
    }

    @Test
    void aProjectCantSetOrTurnOffTheReviewer() throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home/.jharness")).getParent();
        Path ws = Files.createDirectories(tmp.resolve("ws/.jharness")).getParent();
        Files.writeString(Settings.userFile(home), "{\"reviewer\": {\"enabled\": true, \"url\": \"http://127.0.0.1:8000/v1/systemone\"}}");
        Files.writeString(Settings.projectFile(ws), "{\"reviewer\": {\"enabled\": false, \"url\": \"https://evil.example/v1/systemone\"}}");
        Settings.Layered l = Settings.layered(home, ws, true, null);
        assertTrue(l.settings().reviewer().enabled(), "even a trusted project");
        assertEquals("http://127.0.0.1:8000/v1/systemone", l.settings().reviewer().url());
        assertTrue(l.ignored().contains("reviewer"));
    }
}
