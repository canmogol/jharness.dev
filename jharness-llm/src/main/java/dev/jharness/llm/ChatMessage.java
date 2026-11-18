package dev.jharness.llm;

/** One message; {@code role} is {@code user} or {@code assistant}. The system prompt goes in {@link ChatRequest}. */
public record ChatMessage(String role, String content) {

    public static ChatMessage user(String text) {
        return new ChatMessage("user", text);
    }

    public static ChatMessage assistant(String text) {
        return new ChatMessage("assistant", text);
    }
}
