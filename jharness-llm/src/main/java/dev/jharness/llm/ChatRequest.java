package dev.jharness.llm;

import java.util.List;

/** @param maxTokens 0 means the profile's {@code maxTokens} */
public record ChatRequest(String system, List<ChatMessage> messages, int maxTokens) {

    public ChatRequest {
        messages = List.copyOf(messages);
    }
}
