package dev.jharness.testkit;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Base64;
import java.util.Map;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

/**
 * For the native smoke binary (spec 017): a read-only HTTPS git server for {@code git.test} that
 * runs {@code git http-backend} as a CGI over a bare repository with one commit. The certificate
 * is self-signed for {@code git.test} and valid until 2126; {@link #CA_PEM} is passed to git as
 * {@code http.sslCAInfo}. Not product code.
 */
final class SmokeGitServer implements AutoCloseable {

    static final String HOST = "git.test";

    /** PKCS12, password {@code changeit}: an EC key and its self-signed certificate. */
    private static final String KEYSTORE = "MIID5gIBAzCCA5AGCSqGSIb3DQEHAaCCA4EEggN9MIIDeTCCASAGCSqGSIb3DQEHAaCCAREEggENMIIBCTCCAQUGCyqGSIb3DQEMCgECoIG9MIG6MGYGCSqGSIb3DQEFDTBZMDgGCSqGSIb3DQEFDDArBBTkEQxYysWNrsgS7JdEe9vLjKm2WgICJxACASAwDAYIKoZIhvcNAgkFADAdBglghkgBZQMEASoEEBmeyFECdHSrxktrPosafzkEUGzvavxX50Bii4vGbd4LGbyAG0S+lvW/FpFzCm91XYCHkDnpiwwIFZCYMku18SHGWv51WDVEA30ZsNKwmd7TMT+6QppQ8bKNLefHk8TO5IQFMTYwEQYJKoZIhvcNAQkUMQQeAgBzMCEGCSqGSIb3DQEJFTEUBBJUaW1lIDE3OTUzNjU1ODQ1MzUwggJRBgkqhkiG9w0BBwagggJCMIICPgIBADCCAjcGCSqGSIb3DQEHATBmBgkqhkiG9w0BBQ0wWTA4BgkqhkiG9w0BBQwwKwQUXsDHzTJRfUzJ2CLv9AwDr1jUFjACAicQAgEgMAwGCCqGSIb3DQIJBQAwHQYJYIZIAWUDBAEqBBDL2+UtnU85A42gYKUTPm1DgIIBwHKkKyAKjyEvfW9oNw2ioXXSIlEGRAIO7ovTR0PTdDRdjjmjX59OFNLJyJzyGFoHez1tYyy5DPzSkZXLZcnIg85vvUPjp+a8vkFKY7k4BFt8STMicJrAlqAuUsbgOavc3xtFnCevuucUyODpGbuMe0s7e8QojXytqnCAKpFZ/Z/dQZWmLpmccOnr+BXETDS49g9+SVPm+kk5EOWDtixldt4/vgXoM1jEaVykgdJFXJNZ8ostuvFfmMt36MZMprPA6ichRvrhrgjMGmI7uI5gFSxxCVg42uRGiDvT8r8JBf4KydPl+Z/v0xWi7+I5EcqdaEx0k/OB3IhQxKhb99KMWMuTYUxRL7H/mLqcXe7Y7Tdu+Ow9k6Kk2j8O5AdhuCVrnui6PSHsjaERZF0OprWLv1VqQardgE7SKGgn0Ljp+5hQYhPVZh4tUlaqBRh/HbwjFDjQEDXHTDfkE3cr7lqUo5lIKavdTOwszTz01swJhPqGh7P4V90BGeFxMbjNH20KiWw/xpO1ScTy8Qw6+3El2+vOpeAMzH4HV55ugr8P8sosV7PbHbr3hfcOeEectFkPugEPYjqmyhjYr5ea+aXXeJcwTTAxMA0GCWCGSAFlAwQCAQUABCB8uTiKJd3VqJ0+MwfariBdYsQGsiA70M9ZH+vX/w437gQUOJWGiu/3K4lghYmCFscn4uTPTEICAicQ";

    static final String CA_PEM = """
            -----BEGIN CERTIFICATE-----
            MIIBUjCB+qADAgECAghHGS/8yVbnujAKBggqhkjOPQQDAzATMREwDwYDVQQDEwhn
            aXQudGVzdDAgFw0yNjExMjIxNjM5NDRaGA8yMTI2MTAyOTE2Mzk0NFowEzERMA8G
            A1UEAxMIZ2l0LnRlc3QwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAATD37J/8HZp
            OzIXBVbrvPuaHTxAwTe+57EIknjYxhopt53vZAR6lAGgFaBPSuwwX/fbb222XH51
            SeqlwjizE8lEozYwNDAdBgNVHQ4EFgQURG0c21P4dVTPPHYRPznsS/U1JLAwEwYD
            VR0RBAwwCoIIZ2l0LnRlc3QwCgYIKoZIzj0EAwMDRwAwRAIgIfMN0cw38tUN9XnJ
            6DjrU+t2r+4ymLkO88M5PB/uljcCIDJvUbGr6MsFYNf8IcxrEzmAJGUGnXZ3HQ/x
            bgaq9z2c
            -----END CERTIFICATE-----
            """;

    final Path root;
    final Path ca;
    private final Path git;
    private final HttpsServer server;

    SmokeGitServer(Path dir, Path git) throws Exception {
        this.git = git;
        root = Files.createDirectories(dir.resolve("srv"));
        run(root, git.toString(), "init", "-q", "--bare", "-b", "main", "repo.git");
        Path seed = Files.createDirectories(dir.resolve("seed"));
        run(seed, git.toString(), "init", "-q", "-b", "main");
        Files.writeString(seed.resolve("README.md"), "served over https\n");
        run(seed, git.toString(), "add", "README.md");
        run(seed, git.toString(), "-c", "user.name=S", "-c", "user.email=s@example.invalid", "commit", "-q", "-m", "seed");
        run(seed, git.toString(), "push", "-q", root.resolve("repo.git").toString(), "main");
        ca = dir.resolve("ca.pem");
        Files.writeString(ca, CA_PEM);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(new ByteArrayInputStream(Base64.getDecoder().decode(KEYSTORE)), "changeit".toCharArray());
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(kmf.getKeyManagers(), null, null);
        server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.createContext("/", this::cgi);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    private static void run(Path dir, String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException(String.join(" ", cmd) + " failed: " + out);
        }
    }

    String url() {
        return "https://" + HOST + ":" + server.getAddress().getPort() + "/repo.git";
    }

    private void cgi(HttpExchange x) throws IOException {
        try (x) {
            byte[] body = x.getRequestBody().readAllBytes();
            ProcessBuilder pb = new ProcessBuilder(git.toString(), "http-backend");
            Map<String, String> env = pb.environment();
            String query = x.getRequestURI().getRawQuery();
            env.put("GIT_PROJECT_ROOT", root.toString());
            env.put("GIT_HTTP_EXPORT_ALL", "1");
            env.put("REQUEST_METHOD", x.getRequestMethod());
            env.put("PATH_INFO", x.getRequestURI().getPath());
            env.put("QUERY_STRING", query == null ? "" : query);
            env.put("CONTENT_LENGTH", Integer.toString(body.length));
            env.put("REMOTE_ADDR", "127.0.0.1");
            for (String[] h : new String[][] {{"Content-Type", "CONTENT_TYPE"}, {"Content-Encoding", "HTTP_CONTENT_ENCODING"},
                    {"Git-Protocol", "GIT_PROTOCOL"}}) {
                String v = x.getRequestHeaders().getFirst(h[0]);
                if (v != null) {
                    env.put(h[1], v);
                }
            }
            Process p = pb.start();
            try (OutputStream in = p.getOutputStream()) {
                in.write(body);
            }
            byte[] out = p.getInputStream().readAllBytes();
            String text = new String(out, StandardCharsets.ISO_8859_1);
            int split = text.indexOf("\r\n\r\n");
            int skip = 4;
            if (split < 0) {
                split = text.indexOf("\n\n");
                skip = 2;
            }
            if (split < 0) {
                x.sendResponseHeaders(502, -1);
                return;
            }
            int status = 200;
            for (String line : text.substring(0, split).split("\r?\n")) {
                int c = line.indexOf(':');
                if (c > 0) {
                    String k = line.substring(0, c).strip();
                    String v = line.substring(c + 1).strip();
                    if (k.equalsIgnoreCase("Status")) {
                        status = Integer.parseInt(v.substring(0, 3));
                    } else {
                        x.getResponseHeaders().add(k, v);
                    }
                }
            }
            int n = out.length - split - skip;
            x.sendResponseHeaders(status, n == 0 ? -1 : n);
            if (n > 0) {
                x.getResponseBody().write(out, split + skip, n);
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
