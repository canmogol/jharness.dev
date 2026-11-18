package dev.jharness.agent;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SystemPromptTest {

    @Test
    void containsApiExamplesRulesAndContext() {
        String p = SystemPrompt.build("{\"workspace\":\"/w\",\"git\":{\"branch\":\"main\"}}");
        assertTrue(p.contains("declare const api"), "api.d.ts");
        assertTrue(p.contains("editFile(path: string, oldText: string, newText: string)"));
        assertTrue(p.contains("api.git({op: \"commit\""), "examples");
        assertTrue(p.contains("One `js` block per reply"), "rules");
        assertTrue(p.contains("reply without any code block"), "rules");
        assertTrue(p.contains("\"branch\":\"main\""), "context");
        assertFalse(p.contains("{{"), "unfilled slot");
    }
}
