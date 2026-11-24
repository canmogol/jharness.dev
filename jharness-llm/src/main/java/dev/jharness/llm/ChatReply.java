package dev.jharness.llm;

/**
 * @param text the reply; the only part searched for code (D-046)
 * @param thinking the model's thinking text, or empty; never run or sent back (D-046)
 * @param stopReason as the provider reported it, e.g. {@code stop}, {@code end_turn}, {@code max_tokens}
 * @param inputTokens the whole prompt, cached parts included (spec 040)
 * @param cacheReadTokens the part of the prompt read from the provider's cache, or 0
 * @param cacheWriteTokens the part of the prompt written to the provider's cache, or 0
 */
public record ChatReply(String text, String thinking, String stopReason, int inputTokens, int outputTokens,
        int cacheReadTokens, int cacheWriteTokens) {

    /** A reply with no cache counts. */
    public ChatReply(String text, String thinking, String stopReason, int inputTokens, int outputTokens) {
        this(text, thinking, stopReason, inputTokens, outputTokens, 0, 0);
    }
}
