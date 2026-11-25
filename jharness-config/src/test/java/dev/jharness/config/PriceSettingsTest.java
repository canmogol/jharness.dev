package dev.jharness.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Spec 049, AC-01: a model profile's "price", in US dollars per million tokens. */
class PriceSettingsTest {

    static ModelPrice price(String profile) throws Exception {
        return ContextSettingsTest.parse(profile, null).models().all().get("m").price();
    }

    @Test
    void noPriceByDefault() throws Exception {
        assertNull(price(PromptCacheSettingsTest.CLAUDE + "}"));
        assertNull(price(ContextSettingsTest.LOCAL + "}"));
    }

    @Test
    void cachePricesDefaultByProvider() throws Exception {
        ModelPrice c = price(PromptCacheSettingsTest.CLAUDE + ", \"price\": {\"input\": 3, \"output\": 15}}");
        assertEquals(new ModelPrice(3, 15, 0.3, 3.75), new ModelPrice(c.input(), c.output(), Math.round(c.cacheRead() * 100) / 100.0, c.cacheWrite()));
        ModelPrice l = price(ContextSettingsTest.LOCAL + ", \"price\": {\"input\": 0.5, \"output\": 1, \"cacheRead\": 0.05}}");
        assertEquals(new ModelPrice(0.5, 1, 0.05, 0.5), l, "openai-compatible: the input price unless set");
        assertEquals(new ModelPrice(0, 0, 0, 0), price(ContextSettingsTest.LOCAL + ", \"price\": {\"input\": 0, \"output\": 0}}"), "a free local model");
    }

    @Test
    void costCountsCachedTokensAtTheirPrice() {
        ModelPrice p = new ModelPrice(3, 15, 0.3, 3.75);
        // 10,000 prompt tokens: 6,000 read from the cache, 1,000 written to it, 3,000 fresh; 2,000 out.
        assertEquals((3_000 * 3 + 6_000 * 0.3 + 1_000 * 3.75 + 2_000 * 15) / 1e6, p.cost(10_000, 2_000, 6_000, 1_000), 1e-12);
        assertEquals(0, p.cost(0, 0, 0, 0));
        assertEquals(6_000 * 0.3 / 1e6, p.cost(5_000, 0, 6_000, 0), 1e-12, "never a negative fresh count");
    }

    @Test
    void refusesBadPrices() {
        String c = PromptCacheSettingsTest.CLAUDE + ", \"price\": ";
        for (String[] t : new String[][] {
                {c + "3}", "price: expected an object"},
                {c + "{\"output\": 15}}", "price.input is required"},
                {c + "{\"input\": 3}}", "price.output is required"},
                {c + "{\"input\": -1, \"output\": 15}}", "price.input: expected US dollars per million tokens"},
                {c + "{\"input\": \"3\", \"output\": 15}}", "price.input: expected"},
                {c + "{\"input\": 3, \"output\": 20000}}", "from 0 to 10000"},
                {c + "{\"input\": 3, \"output\": 15, \"currency\": \"EUR\"}}", "price.currency is not a known price"}}) {
            ConfigException e = assertThrows(ConfigException.class, () -> ContextSettingsTest.parse(t[0], null), t[0]);
            assertTrue(e.getMessage().contains(t[1]), e.getMessage());
        }
    }
}
