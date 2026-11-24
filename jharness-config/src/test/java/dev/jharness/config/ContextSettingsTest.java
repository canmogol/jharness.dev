package dev.jharness.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Spec 039, AC-01: contextWindow per profile, autoCompact and compactAt in the agent section. */
class ContextSettingsTest {

    static Settings parse(String profile, String agent) throws ConfigException {
        return Settings.parse("{\"model\": \"m\", \"models\": {\"m\": " + profile + "}" + (agent == null ? "" : ", \"agent\": " + agent) + "}", "t");
    }

    static final String LOCAL = "{\"provider\": \"openai-compatible\", \"baseUrl\": \"http://localhost:11434/v1\", \"model\": \"q\"";

    @Test
    void windowsDefaultByProvider() throws Exception {
        assertEquals(32_768, parse(LOCAL + "}", null).models().all().get("m").contextWindow());
        assertEquals(200_000, parse("{\"provider\": \"anthropic\", \"model\": \"c\"}", null).models().all().get("m").contextWindow());
        assertEquals(131_072, parse(LOCAL + ", \"contextWindow\": 131072}", null).models().all().get("m").contextWindow());
    }

    @Test
    void refusesBadWindows() {
        for (String[] c : new String[][] {
                {LOCAL + ", \"contextWindow\": 100}", "contextWindow: expected a whole number of tokens from 2048"},
                {LOCAL + ", \"contextWindow\": \"big\"}", "contextWindow: expected a whole number"},
                {LOCAL + ", \"contextWindow\": 8192, \"maxTokens\": 8192}", "maxTokens (8192) must be below contextWindow (8192)"}}) {
            ConfigException e = assertThrows(ConfigException.class, () -> parse(c[0], null), c[0]);
            assertTrue(e.getMessage().contains(c[1]), e.getMessage());
        }
    }

    @Test
    void compactionSettings() throws Exception {
        AgentSettings d = Settings.parse("{}", "t").agent();
        assertTrue(d.autoCompact());
        assertEquals(0.80, d.compactAt());
        AgentSettings a = parse(LOCAL + "}", "{\"autoCompact\": false, \"compactAt\": 0.6}").agent();
        assertFalse(a.autoCompact());
        assertEquals(0.6, a.compactAt());
        for (String[] c : new String[][] {{"{\"compactAt\": 0.99}", "from 0.5 to 0.95"}, {"{\"autoCompact\": 1}", "true or false"}}) {
            ConfigException e = assertThrows(ConfigException.class, () -> parse(LOCAL + "}", c[0]));
            assertTrue(e.getMessage().contains(c[1]), e.getMessage());
        }
    }
}
