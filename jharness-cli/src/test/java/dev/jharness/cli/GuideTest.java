package dev.jharness.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import dev.jharness.agent.SystemPrompt;
import picocli.CommandLine;

/** Spec 063: the capabilities and commands guide names every command, matches the README, and is in the system prompt. */
class GuideTest {

    static final String GUIDE = SystemPrompt.guide();

    @Test
    void everySlashCommandHasALine() {
        for (String c : Tui.COMMANDS.keySet()) {
            assertTrue(GUIDE.contains("| `" + c + "`") || GUIDE.contains("| `" + c + " "), c + " is in /help but not in guide.md");
        }
    }

    @Test
    void everyCommandAndOptionOfTheCliIsNamed() {
        CommandLine cl = new CommandLine(new Main());
        for (String sub : cl.getSubcommands().keySet()) {
            assertTrue(GUIDE.contains("`jharness " + sub + "`"), "jharness " + sub + " is not in guide.md");
        }
        for (var o : cl.getCommandSpec().options()) {
            if (o.hidden()) {
                continue;
            }
            assertTrue(GUIDE.contains("`" + o.longestName()), o.longestName() + " is not in guide.md");
        }
    }

    @Test
    void theReadmeHasTheSameText() throws Exception {
        String readme = Files.readString(Path.of("..", "README.md"));
        int start = readme.indexOf("-->\n", readme.indexOf("<!-- guide:")) + 4;
        int end = readme.indexOf("\n<!-- /guide -->");
        assertTrue(start > 4 && end > start, "the README's guide markers");
        assertEquals(GUIDE, readme.substring(start, end), "README.md's \"What it does\" differs from guide.md");
    }

    @Test
    void theAgentGetsIt() {
        String prompt = SystemPrompt.build("{}");
        assertTrue(prompt.contains("# JHarness itself"), prompt);
        assertTrue(prompt.contains(GUIDE), "the system prompt carries the guide");
        assertFalse(prompt.contains("{{GUIDE}}"));
    }
}
