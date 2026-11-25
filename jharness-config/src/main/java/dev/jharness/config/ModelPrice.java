package dev.jharness.config;

/**
 * What a model profile's tokens cost, in US dollars per million tokens (spec 049, D-084). Set by the user;
 * JHarness ships no price list, since prices change and differ between accounts and servers.
 *
 * @param cacheRead the price of prompt tokens read from the cache
 * @param cacheWrite the price of prompt tokens written to the cache
 */
public record ModelPrice(double input, double output, double cacheRead, double cacheWrite) {

    /** The highest price per million tokens a setting may give. */
    public static final double MAX = 10_000;

    /**
     * The cost of one request, in dollars.
     *
     * @param inputTokens the whole prompt, cached parts included (spec 040)
     */
    public double cost(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens) {
        long fresh = Math.max(0, inputTokens - cacheReadTokens - cacheWriteTokens);
        return (fresh * input + cacheReadTokens * cacheRead + cacheWriteTokens * cacheWrite + outputTokens * output) / 1_000_000.0;
    }
}
