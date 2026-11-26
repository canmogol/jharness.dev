package dev.jharness.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringWriter;

import org.junit.jupiter.api.Test;

/** Spec 067: while one thread shows a question, the others' output waits, and comes after it in one piece. */
class AboveWriterTest {

    @Test
    void otherThreadsWaitWhileAQuestionIsShown() throws Exception {
        StringWriter screen = new StringWriter();
        AboveWriter w = new AboveWriter(screen, null);
        w.hold();
        Thread other = Thread.ofPlatform().start(() -> {
            try {
                w.write("main turn line\n");
                w.write("partial");
                w.flush();
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        other.join();
        w.write("Allow writeFile: write a.txt?\n[y] once  [a] always  [n] no: ");
        w.flush();
        assertEquals("Allow writeFile: write a.txt?\n[y] once  [a] always  [n] no: ", screen.toString(), "nothing in between");
        w.write("y\n");
        w.release();
        assertEquals("Allow writeFile: write a.txt?\n[y] once  [a] always  [n] no: y\nmain turn line\npartial", screen.toString());
        w.write("after\n");
        assertTrue(screen.toString().endsWith("after\n"), "released: " + screen);
    }
}
