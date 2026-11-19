package dev.jharness.llm.http;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * The only place an {@link HttpClient} is built (constitution Article V; enforced by the
 * architecture test). Every client goes through the host egress proxy (D-025, spec 011) once
 * {@link #useProxy} has been called, and answers the proxy's credential challenge.
 */
public final class HttpClients {

    static {
        // The JDK refuses Basic credentials on CONNECT tunnels unless this is cleared; the egress
        // proxy needs them (spec 011). Set before the first client is built.
        if (System.getProperty("jdk.http.auth.tunneling.disabledSchemes") == null) {
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        }
    }

    private static volatile ProxySelector proxy = HttpClient.Builder.NO_PROXY;
    private static volatile Authenticator authenticator;

    private HttpClients() {}

    /** Called by the session when its egress proxy starts; clients built afterwards use it. */
    public static void useProxy(InetSocketAddress address, PasswordAuthentication credentials) {
        authenticator = new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                if (getRequestorType() == RequestorType.PROXY && getRequestingPort() == address.getPort()) {
                    return credentials;
                }
                return null;
            }
        };
        proxy = ProxySelector.of(address);
    }

    public static HttpClient create() {
        HttpClient.Builder b = HttpClient.newBuilder()
                .proxy(proxy)
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1);
        Authenticator a = authenticator;
        if (a != null) {
            b.authenticator(a);
        }
        return b.build();
    }
}
