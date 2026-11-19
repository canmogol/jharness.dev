package dev.jharness.llm;

/**
 * @param text the reply; the only part searched for code (D-046)
 * @param thinking the model's thinking text, or empty; never run or sent back (D-046)
 * @param stopReason as the provider reported it, e.g. {@code stop}, {@code end_turn}, {@code max_tokens}
 */
public record ChatReply(String text, String thinking, String stopReason, int inputTokens, int outputTokens) {
}
