package dev.jharness.llm;

import java.util.List;

/**
 * @param maxTokens 0 means the profile's {@code maxTokens}
 * @param cache whether the request may mark its prefix for the prompt cache (spec 040); one-off requests,
 *        such as a compaction summary, don't
 */
public record ChatRequest(String system, List<ChatMessage> messages, int maxTokens, boolean cache) {

    public ChatRequest {
        messages = List.copyOf(messages);
    }

    /** A request that may use the prompt cache. */
    public ChatRequest(String system, List<ChatMessage> messages, int maxTokens) {
        this(system, messages, maxTokens, true);
    }
}
