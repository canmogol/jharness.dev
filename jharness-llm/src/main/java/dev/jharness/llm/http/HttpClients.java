package dev.jharness.llm.http;

import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * The only place an {@link HttpClient} is built (constitution Article V; enforced by the
 * architecture test). Until the egress proxy lands in P2 (D-025), model traffic goes directly to
 * the configured endpoint; the proxy will be set here with {@link #useProxy}.
 */
public final class HttpClients {

    private static volatile ProxySelector proxy = HttpClient.Builder.NO_PROXY;

    private HttpClients() {}

    /** Called once by the host when the egress proxy starts (P2). */
    public static void useProxy(ProxySelector selector) {
        proxy = selector;
    }

    public static HttpClient create() {
        return HttpClient.newBuilder()
                .proxy(proxy)
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }
}
