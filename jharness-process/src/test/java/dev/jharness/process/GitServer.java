package dev.jharness.process;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import dev.jharness.events.EventBus;
import dev.jharness.events.Origin;
import dev.jharness.net.EgressProxy;
import dev.jharness.net.NetworkRules;
import dev.jharness.net.RulesFile;

/**
 * Test fixture for spec 017: an HTTPS git server ({@code git http-backend} as a CGI) for
 * {@code git.test}, with a self-signed certificate, behind the real egress proxy, which allows
 * {@code git.test} and resolves it to loopback. Pushes need {@code Authorization} with
 * {@link #TOKEN} when {@link #requireAuth} is set.
 */
final class GitServer implements AutoCloseable {

    static final String HOST = "git.test";
    static final String TOKEN = "ghp_test_token_0123456789";

    final Path root;
    final Path ca;
    final HttpsServer server;
    final EgressProxy proxy;
    final ChildSandbox sandbox;
    final Path sessionDir;
    final Path git;
    final List<String> authorizations = new CopyOnWriteArrayList<>();
    volatile boolean requireAuth = true;

    GitServer(Path tmp, Path workspace, EventBus bus) throws Exception {
        git = Executables.resolve("git", workspace).orElseThrow().path();
        root = Files.createDirectories(tmp.resolve("srv"));
        sh(root, git.toString(), "init", "-q", "--bare", "-b", "main", "repo.git");
        sh(root, git.toString(), "--git-dir=repo.git", "config", "http.receivepack", "true");
        Path seed = Files.createDirectories(tmp.resolve("seed"));
        sh(seed, git.toString(), "init", "-q", "-b", "main");
        Files.writeString(seed.resolve("README.md"), "seed\n");
        sh(seed, git.toString(), "add", "README.md");
        sh(seed, git.toString(), "-c", "user.name=T", "-c", "user.email=t@example.invalid", "commit", "-q", "-m", "seed");
        sh(seed, git.toString(), "push", "-q", root.resolve("repo.git").toString(), "main");

        Path ks = tmp.resolve("server.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        sh(tmp, keytool, "-genkeypair", "-alias", "s", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=" + HOST,
                "-ext", "SAN=dns:" + HOST, "-validity", "2", "-storetype", "PKCS12", "-keystore", ks.toString(), "-storepass", "changeit");
        ca = tmp.resolve("ca.pem");
        sh(tmp, keytool, "-exportcert", "-rfc", "-alias", "s", "-keystore", ks.toString(), "-storepass", "changeit", "-file", ca.toString());
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(ks)) {
            store.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, "changeit".toCharArray());
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(kmf.getKeyManagers(), null, null);
        server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.createContext("/", this::cgi);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        Path g = tmp.resolve("rules/general");
        Files.createDirectories(g.getParent());
        Files.writeString(g, "default deny\nallow " + HOST + "\n");
        proxy = EgressProxy.start(() -> new NetworkRules(RulesFile.load(g), RulesFile.empty(tmp.resolve("rules/project")), true, List.of()),
                null, bus, () -> Origin.main(1), tmp.resolve("rules/project"),
                h -> h.equals(HOST) ? new InetAddress[] {InetAddress.getLoopbackAddress()}
                        : new InetAddress[] {InetAddress.ofLiteral("93.184.216.34")});
        sessionDir = Files.createTempDirectory("jh-git-");
        Path sock = sessionDir.resolve("proxy.sock");
        ChildSandbox base = TestSandboxes.in(workspace);
        if (base.mechanism() == ChildSandbox.Mechanism.BUBBLEWRAP && base.available()) {
            proxy.serveUnix(sock);
        }
        var cred = proxy.credentials();
        sandbox = base.withProxy(new ChildSandbox.ProxyRoute(proxy.address().getPort(), sock, cred.getUserName(), new String(cred.getPassword())),
                ChildSandbox.selfBridgeCommand());
    }

    int port() {
        return server.getAddress().getPort();
    }

    /** The host key for {@code git.credentials}: host and port. */
    String hostKey() {
        return HOST + ":" + port();
    }

    String url() {
        return "https://" + hostKey() + "/repo.git";
    }

    /** The test-only config that makes git trust the self-signed certificate. */
    List<Map.Entry<String, String>> caConfig() {
        return List.of(Map.entry("http.sslCAInfo", ca.toString()));
    }

    /** {@code git log --oneline} of the served repository. */
    String log() throws Exception {
        return sh(root, git.toString(), "--git-dir=repo.git", "log", "--oneline", "main");
    }

    static String sh(Path dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException(String.join(" ", cmd) + ": " + out);
        }
        return out;
    }

    private void cgi(HttpExchange x) throws IOException {
        try (x) {
            String auth = x.getRequestHeaders().getFirst("Authorization");
            if (auth != null) {
                authorizations.add(auth);
            }
            String query = x.getRequestURI().getRawQuery();
            String path = x.getRequestURI().getPath();
            boolean push = path.endsWith("/git-receive-pack") || "service=git-receive-pack".equals(query);
            if (push && requireAuth && !("Basic " + GitOperations.basic(TOKEN)).equals(auth)) {
                x.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"git\"");
                x.sendResponseHeaders(401, -1);
                return;
            }
            byte[] body = x.getRequestBody().readAllBytes();
            ProcessBuilder pb = new ProcessBuilder(git.toString(), "http-backend");
            Map<String, String> env = pb.environment();
            env.put("GIT_PROJECT_ROOT", root.toString());
            env.put("GIT_HTTP_EXPORT_ALL", "1");
            env.put("REQUEST_METHOD", x.getRequestMethod());
            env.put("PATH_INFO", path);
            env.put("QUERY_STRING", query == null ? "" : query);
            env.put("CONTENT_LENGTH", Integer.toString(body.length));
            env.put("REMOTE_USER", "test");
            env.put("REMOTE_ADDR", "127.0.0.1");
            String type = x.getRequestHeaders().getFirst("Content-Type");
            if (type != null) {
                env.put("CONTENT_TYPE", type);
            }
            String enc = x.getRequestHeaders().getFirst("Content-Encoding");
            if (enc != null) {
                env.put("HTTP_CONTENT_ENCODING", enc);
            }
            String proto = x.getRequestHeaders().getFirst("Git-Protocol");
            if (proto != null) {
                env.put("GIT_PROTOCOL", proto);
            }
            Process p = pb.start();
            try (OutputStream in = p.getOutputStream()) {
                in.write(body);
            }
            byte[] out = p.getInputStream().readAllBytes();
            int split = indexOf(out, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            int skip = 4;
            if (split < 0) {
                split = indexOf(out, "\n\n".getBytes(StandardCharsets.US_ASCII));
                skip = 2;
            }
            int status = 200;
            for (String line : new String(out, 0, Math.max(split, 0), StandardCharsets.ISO_8859_1).split("\r?\n")) {
                int c = line.indexOf(':');
                if (c <= 0) {
                    continue;
                }
                String k = line.substring(0, c).strip();
                String v = line.substring(c + 1).strip();
                if (k.equalsIgnoreCase("Status")) {
                    status = Integer.parseInt(v.substring(0, 3));
                } else {
                    x.getResponseHeaders().add(k, v);
                }
            }
            ByteArrayOutputStream rest = new ByteArrayOutputStream();
            rest.write(out, split + skip, out.length - split - skip);
            byte[] b = rest.toByteArray();
            x.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
            if (b.length > 0) {
                x.getResponseBody().write(b);
            }
        } catch (RuntimeException e) {
            x.sendResponseHeaders(500, -1);
        }
    }

    private static int indexOf(byte[] a, byte[] b) {
        outer:
        for (int i = 0; i + b.length <= a.length; i++) {
            for (int j = 0; j < b.length; j++) {
                if (a[i + j] != b[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    @Override
    public void close() throws IOException {
        proxy.close();
        server.stop(0);
    }
}
