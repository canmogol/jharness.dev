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
        assertFalse(p.contains("Project instructions"));
    }

    @Test
    void memoryFilesGoInUnderTheirHeadingsAndAreCut() {
        String p = SystemPrompt.build("{}", "Run tests with mvn -q.", "Answer briefly.");
        assertTrue(p.contains("# Project instructions (JHARNESS.md)\n\nRun tests with mvn -q."), p);
        assertTrue(p.contains("# Your user's instructions (~/.jharness/JHARNESS.md)\n\nAnswer briefly."));
        String big = SystemPrompt.build("{}", "x".repeat(40_000), null);
        assertTrue(big.contains("[cut at 32 KB]"));
        assertFalse(big.contains("x".repeat(32 * 1024 + 1)));
    }
}
