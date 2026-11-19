package dev.jharness.llm;

import java.util.function.Consumer;

/**
 * Splits a leading {@code <think>…</think>} off streamed reply text (spec 010 AC-03): the part
 * inside the tags goes to {@code thinking}, the rest to {@code text}. Tags may be split across
 * chunks. Leading blank lines before the reply are dropped.
 */
final class ThinkSplitter {

    private static final String OPEN = "<think>";
    private static final String CLOSE = "</think>";

    private enum State { START, THINK, AFTER, TEXT }

    private final Consumer<String> text;
    private final Consumer<String> thinking;
    private final StringBuilder held = new StringBuilder();
    private State state = State.START;

    ThinkSplitter(Consumer<String> text, Consumer<String> thinking) {
        this.text = text;
        this.thinking = thinking;
    }

    void feed(String chunk) {
        if (chunk.isEmpty()) {
            return;
        }
        if (state == State.TEXT) {
            text.accept(chunk);
            return;
        }
        held.append(chunk);
        step();
    }

    private void step() {
        while (true) {
            switch (state) {
                case START -> {
                    String t = held.toString().stripLeading();
                    if (t.isEmpty()) {
                        return;
                    }
                    if (t.startsWith(OPEN)) {
                        held.setLength(0);
                        held.append(t.substring(OPEN.length()));
                        state = State.THINK;
                    } else if (OPEN.startsWith(t)) {
                        return;
                    } else {
                        held.setLength(0);
                        held.append(t);
                        state = State.AFTER;
                    }
                }
                case THINK -> {
                    int end = held.indexOf(CLOSE);
                    if (end >= 0) {
                        emitThinking(held.substring(0, end));
                        String rest = held.substring(end + CLOSE.length());
                        held.setLength(0);
                        held.append(rest);
                        state = State.AFTER;
                    } else {
                        int safe = held.length() - (CLOSE.length() - 1);
                        if (safe > 0) {
                            emitThinking(held.substring(0, safe));
                            held.delete(0, safe);
                        }
                        return;
                    }
                }
                case AFTER -> {
                    String t = held.toString().stripLeading();
                    if (t.isEmpty()) {
                        return;
                    }
                    held.setLength(0);
                    state = State.TEXT;
                    text.accept(t);
                    return;
                }
                case TEXT -> {
                    return;
                }
            }
        }
    }

    private void emitThinking(String s) {
        if (!s.isEmpty()) {
            thinking.accept(s);
        }
    }

    /** At the end of the stream: an unclosed {@code <think>} is all thinking; a partial tag at the start is text. */
    void finish() {
        if (held.isEmpty()) {
            return;
        }
        String rest = held.toString();
        held.setLength(0);
        switch (state) {
            case THINK -> emitThinking(rest);
            case START, AFTER -> {
                String t = rest.stripLeading();
                if (!t.isEmpty()) {
                    text.accept(t);
                }
            }
            case TEXT -> text.accept(rest);
        }
        state = State.TEXT;
    }
}
