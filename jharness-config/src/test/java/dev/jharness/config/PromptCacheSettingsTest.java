package dev.jharness.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Spec 040, AC-01: promptCache on anthropic profiles. */
class PromptCacheSettingsTest {

    static final String CLAUDE = "{\"provider\": \"anthropic\", \"model\": \"c\"";

    @Test
    void onForAnthropicByDefault() throws Exception {
        assertTrue(ContextSettingsTest.parse(CLAUDE + "}", null).models().all().get("m").promptCache());
        assertFalse(ContextSettingsTest.parse(CLAUDE + ", \"promptCache\": false}", null).models().all().get("m").promptCache());
        assertFalse(ContextSettingsTest.parse(ContextSettingsTest.LOCAL + "}", null).models().all().get("m").promptCache());
    }

    @Test
    void refusesBadValues() {
        for (String[] c : new String[][] {
                {CLAUDE + ", \"promptCache\": \"yes\"}", "promptCache: expected true or false"},
                {ContextSettingsTest.LOCAL + ", \"promptCache\": true}", "promptCache applies only to anthropic profiles"}}) {
            ConfigException e = assertThrows(ConfigException.class, () -> ContextSettingsTest.parse(c[0], null), c[0]);
            assertTrue(e.getMessage().contains(c[1]), e.getMessage());
        }
    }
}
