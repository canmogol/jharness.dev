package dev.jharness.config;

/**
 * One named model profile (D-032). API keys never appear here; {@code apiKeyEnv} names the
 * environment variable that holds the key.
 *
 * @param provider {@code openai-compatible} or {@code anthropic}
 * @param baseUrl the API base URL; for {@code anthropic} defaults to {@code https://api.anthropic.com}
 * @param apiKeyEnv environment variable with the key, or null for none (local servers)
 * @param maxTokens largest reply to ask for
 */
public record ModelProfile(String name, String provider, String baseUrl, String model, String apiKeyEnv,
        int maxTokens, String notes) {

    public static final String OPENAI_COMPATIBLE = "openai-compatible";
    public static final String ANTHROPIC = "anthropic";
}
