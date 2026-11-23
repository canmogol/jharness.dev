package dev.jharness.llm;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/** Spec 020: which failures are retried, how long to wait. */
class RetryRulesTest {

    @Test
    void statuses() {
        for (int c : new int[] {408, 409, 429, 500, 502, 503, 504, 529}) {
            assertTrue(HttpProvider.retryableStatus(c), "" + c);
        }
        for (int c : new int[] {400, 401, 403, 404, 413, 422}) {
            assertFalse(HttpProvider.retryableStatus(c), "" + c);
        }
    }

    @Test
    void connectionFailures() {
        assertTrue(HttpProvider.retryableIo(new ConnectException("Connection refused")));
        assertTrue(HttpProvider.retryableIo(new HttpConnectTimeoutException("HTTP connect timed out")));
        assertTrue(HttpProvider.retryableIo(new IOException("Connection reset")));
        assertTrue(HttpProvider.retryableIo(new IOException("Tunnel failed, got: 502")));
        assertFalse(HttpProvider.retryableIo(new HttpTimeoutException("request timed out")), "the response timeout is the profile's patience");
        assertFalse(HttpProvider.retryableIo(new IOException("Tunnel failed, got: 403")), "the network rules refused it");
        assertFalse(HttpProvider.retryableIo(new IOException("Unable to tunnel through proxy. Proxy returns \"HTTP/1.1 407\"")));
    }

    @Test
    void retryAfterHeaders() {
        Instant now = Instant.parse("2026-11-23T10:00:00Z");
        assertEquals(Duration.ofSeconds(7), HttpProvider.retryAfter(null, "7", now));
        assertEquals(Duration.ofMillis(1500), HttpProvider.retryAfter(null, "1.5", now));
        assertEquals(Duration.ofMillis(250), HttpProvider.retryAfter("250", "7", now));
        assertEquals(Duration.ofSeconds(30), HttpProvider.retryAfter(null, "Mon, 23 Nov 2026 10:00:30 GMT", now));
        assertEquals(Duration.ZERO, HttpProvider.retryAfter(null, "Mon, 23 Nov 2026 09:00:00 GMT", now));
        assertNull(HttpProvider.retryAfter(null, null, now));
        assertNull(HttpProvider.retryAfter(null, "soon", now));
    }

    @Test
    void backoffDoublesUpToTheMaximum() {
        for (int i = 0; i < 50; i++) {
            long a = HttpProvider.backoff(1, Duration.ofSeconds(60)).toMillis();
            long d = HttpProvider.backoff(4, Duration.ofSeconds(60)).toMillis();
            long capped = HttpProvider.backoff(9, Duration.ofSeconds(10)).toMillis();
            assertTrue(a >= 500 && a <= 1000, "" + a);
            assertTrue(d >= 4000 && d <= 8000, "" + d);
            assertTrue(capped >= 5000 && capped <= 10_000, "" + capped);
        }
    }
}
