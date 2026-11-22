package dev.jharness.net.http;

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
    private static volatile InetSocketAddress current;

    private HttpClients() {}

    /** Called by the session when its egress proxy starts; clients built afterwards use it. */
    public static synchronized void useProxy(InetSocketAddress address, PasswordAuthentication credentials) {
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
        current = address;
    }

    /** Called when that session's proxy closes; a newer session's proxy is left alone. */
    public static synchronized void stopProxy(InetSocketAddress address) {
        if (address.equals(current)) {
            proxy = HttpClient.Builder.NO_PROXY;
            authenticator = null;
            current = null;
        }
    }

    /** Whether a session's egress proxy is set; {@link HttpOperations} refuses to send without one. */
    public static boolean proxied() {
        return current != null;
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
