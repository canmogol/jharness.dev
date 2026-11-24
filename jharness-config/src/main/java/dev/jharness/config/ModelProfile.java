package dev.jharness.config;

import java.time.Duration;

/**
 * One named model profile (D-032). API keys never appear here; {@code apiKeyEnv} names the
 * environment variable that holds the key.
 *
 * @param provider {@code openai-compatible} or {@code anthropic}
 * @param baseUrl the API base URL; for {@code anthropic} defaults to {@code https://api.anthropic.com}
 * @param apiKeyEnv environment variable with the key, or null for none (local servers)
 * @param maxTokens largest reply to ask for
 * @param retries how many times a failed request may be sent again (spec 020, D-058)
 * @param retryMaxWait the longest JHarness waits before one retry; a longer {@code retry-after} fails at once
 * @param responseTimeout how long to wait for the response to start
 * @param contextWindow the most tokens the model takes in one request, prompt and reply together (spec 039);
 *        32,768 for {@code openai-compatible} and 200,000 for {@code anthropic} unless set
 */
public record ModelProfile(String name, String provider, String baseUrl, String model, String apiKeyEnv,
        int maxTokens, String notes, int retries, Duration retryMaxWait, Duration responseTimeout, int contextWindow) {

    public static final String OPENAI_COMPATIBLE = "openai-compatible";
    public static final String ANTHROPIC = "anthropic";

    public static final int DEFAULT_RETRIES = 4;
    public static final int MAX_RETRIES = 10;
    public static final Duration DEFAULT_RETRY_MAX_WAIT = Duration.ofSeconds(60);
    public static final Duration MAX_RETRY_MAX_WAIT = Duration.ofMinutes(10);
    public static final Duration DEFAULT_RESPONSE_TIMEOUT = Duration.ofSeconds(120);
    public static final Duration MIN_RESPONSE_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration MAX_RESPONSE_TIMEOUT = Duration.ofMinutes(30);
    public static final int DEFAULT_CONTEXT_WINDOW = 32_768;
    public static final int ANTHROPIC_CONTEXT_WINDOW = 200_000;
    public static final int MIN_CONTEXT_WINDOW = 2_048;
    public static final int MAX_CONTEXT_WINDOW = 10_000_000;

    public static int defaultContextWindow(String provider) {
        return ANTHROPIC.equals(provider) ? ANTHROPIC_CONTEXT_WINDOW : DEFAULT_CONTEXT_WINDOW;
    }

    /** A profile with the default retry and timeout settings. */
    public ModelProfile(String name, String provider, String baseUrl, String model, String apiKeyEnv, int maxTokens, String notes) {
        this(name, provider, baseUrl, model, apiKeyEnv, maxTokens, notes, DEFAULT_RETRIES, DEFAULT_RETRY_MAX_WAIT,
                DEFAULT_RESPONSE_TIMEOUT, defaultContextWindow(provider));
    }
}
