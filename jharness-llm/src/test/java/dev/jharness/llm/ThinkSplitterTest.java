package dev.jharness.llm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class ThinkSplitterTest {

    private static String[] split(String... chunks) {
        StringBuilder text = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        ThinkSplitter s = new ThinkSplitter(text::append, thinking::append);
        for (String c : chunks) {
            s.feed(c);
        }
        s.finish();
        return new String[] {text.toString(), thinking.toString()};
    }

    @Test
    void noTags() {
        assertArrayEquals(new String[] {"Hi there", ""}, split("Hi", " there"));
        assertArrayEquals(new String[] {"Hi", ""}, split("\n\n", "Hi"), "leading blank lines are dropped");
        assertArrayEquals(new String[] {"<b>bold</b>", ""}, split("<b>", "bold</b>"));
        assertArrayEquals(new String[] {"a <think>x</think>", ""}, split("a <think>x</think>"), "only a leading block is thinking");
    }

    @Test
    void leadingThinkBlock() {
        assertArrayEquals(new String[] {"Answer.", "plan it"}, split("<think>plan it</think>\n\nAnswer."));
        assertArrayEquals(new String[] {"Answer.", "\nplan it\n"}, split("\n<think>\nplan it\n</think>\n\nAnswer."));
    }

    @Test
    void tagsSplitAcrossChunks() {
        for (List<String> chunks : List.of(
                List.of("<", "thi", "nk>", "step 1", "</th", "ink", ">", "\n", "Done"),
                List.of("<think>step 1<", "/think>Done"),
                List.of("<think>step", " 1</think", ">\n\nDone"))) {
            assertArrayEquals(new String[] {"Done", "step 1"}, split(chunks.toArray(String[]::new)), chunks.toString());
        }
    }

    @Test
    void codeInsideThinkingIsNotText() {
        String[] r = split("<think>maybe\n```js\napi.writeFile('x','y')\n```\n</think>", "Let me check first.");
        assertEquals("Let me check first.", r[0]);
        assertTrue(r[1].contains("api.writeFile"), r[1]);
    }

    @Test
    void endsEarly() {
        assertArrayEquals(new String[] {"", "never closed"}, split("<think>never closed"));
        assertArrayEquals(new String[] {"<thi", ""}, split("<thi"), "a partial tag at the end is text");
        assertArrayEquals(new String[] {"", ""}, split("\n\n"));
    }
}
