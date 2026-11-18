package dev.jharness.llm;

/** @param stopReason as the provider reported it, e.g. {@code stop}, {@code end_turn}, {@code max_tokens} */
public record ChatReply(String text, String stopReason, int inputTokens, int outputTokens) {
}
