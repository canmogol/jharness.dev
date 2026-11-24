package dev.jharness.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The read-only web session viewer (spec 048, D-053, D-083). The binary serves embedded static files and the event
 * stream as Server-Sent Events, on {@code 127.0.0.1} only. Every request needs the random token, a {@code Host} of
 * this address and port, and, when the browser sends one, an {@code Origin} of the same. Only {@code GET} is
 * served: nothing a browser sends can change the session.
 */
public final class SessionViewer implements AutoCloseable {

    /** Most browsers watching at once. */
    public static final int MAX_CLIENTS = 8;
    /** Lines a slow browser may fall behind before it is disconnected. */
    static final int CLIENT_QUEUE = 20_000;
    static final long PING_SECONDS = 15;

    static final Map<String, String[]> FILES = Map.of(
            "/", new String[] {"index.html", "text/html; charset=utf-8"},
            "/app.js", new String[] {"app.js", "text/javascript; charset=utf-8"},
            "/app.css", new String[] {"app.css", "text/css; charset=utf-8"});

    static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; "
            + "base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

    private final HttpServer server;
    private final EventFeed feed;
    private final String token;
    private final int port;
    private final ExecutorService pool;
    private final Semaphore clients = new Semaphore(MAX_CLIENTS);
    private volatile boolean closed;

    private SessionViewer(HttpServer server, EventFeed feed, String token, ExecutorService pool) {
        this.server = server;
        this.feed = feed;
        this.token = token;
        this.port = server.getAddress().getPort();
        this.pool = pool;
    }

    /**
     * @param port 0 for any free port
     */
    public static SessionViewer start(EventFeed feed, int port) throws IOException {
        // The listening socket the architecture test allows here (D-083); it binds to the loopback address only.
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 16);
        byte[] t = new byte[32];
        new SecureRandom().nextBytes(t);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(t);
        ExecutorService pool = Executors.newFixedThreadPool(MAX_CLIENTS + 4, r -> {
            Thread th = new Thread(r, "jharness-view");
            th.setDaemon(true);
            return th;
        });
        SessionViewer v = new SessionViewer(server, feed, token, pool);
        server.setExecutor(pool);
        server.createContext("/", v::handle);
        server.start();
        return v;
    }

    public int port() {
        return port;
    }

    /** The address to open, with the token. */
    public String url() {
        return "http://127.0.0.1:" + port + "/?t=" + token;
    }

    String token() {
        return token;
    }

    private void handle(HttpExchange x) throws IOException {
        try (x) {
            var h = x.getResponseHeaders();
            h.set("Content-Security-Policy", CSP);
            h.set("X-Content-Type-Options", "nosniff");
            h.set("Referrer-Policy", "no-referrer");
            h.set("X-Frame-Options", "DENY");
            h.set("Cross-Origin-Resource-Policy", "same-origin");
            h.set("Cache-Control", "no-store");
            String refused = refused(x);
            if (refused != null) {
                send(x, refused.startsWith("405") ? 405 : refused.startsWith("404") ? 404 : 403, "text/plain; charset=utf-8",
                        refused.getBytes(StandardCharsets.UTF_8));
                return;
            }
            String path = x.getRequestURI().getPath();
            if (path.equals("/events")) {
                events(x);
                return;
            }
            String[] f = FILES.get(path);
            try (InputStream in = SessionViewer.class.getResourceAsStream(f[0])) {
                send(x, 200, f[1], in.readAllBytes());
            }
        }
    }

    /** Why a request is refused, or null. */
    String refused(HttpExchange x) {
        return refused(x.getRequestMethod(), x.getRequestHeaders().getFirst("Host"), x.getRequestHeaders().getFirst("Origin"),
                x.getRequestURI());
    }

    String refused(String method, String host, String origin, URI uri) {
        if (!method.equals("GET")) {
            return "405 only GET: the viewer is read-only";
        }
        if (host == null || !(host.equals("127.0.0.1:" + port) || host.equals("localhost:" + port))) {
            return "403 wrong Host";
        }
        if (origin != null && !(origin.equals("http://127.0.0.1:" + port) || origin.equals("http://localhost:" + port))) {
            return "403 wrong Origin";
        }
        String t = query(uri.getRawQuery(), "t");
        if (t == null || !MessageDigest.isEqual(t.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            return "403 missing or wrong token";
        }
        String path = uri.getPath();
        if (!path.equals("/events") && !FILES.containsKey(path)) {
            return "404 not found";
        }
        return null;
    }

    static String query(String raw, String name) {
        if (raw == null) {
            return null;
        }
        for (String kv : raw.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals(name)) {
                return kv.substring(eq + 1);
            }
        }
        return null;
    }

    private static void send(HttpExchange x, int status, String type, byte[] body) throws IOException {
        x.getResponseHeaders().set("Content-Type", type);
        x.sendResponseHeaders(status, body.length);
        try (OutputStream o = x.getResponseBody()) {
            o.write(body);
        }
    }

    /** Server-Sent Events: the backlog, then live lines, with a ping so a closed tab is noticed. */
    private void events(HttpExchange x) throws IOException {
        if (!clients.tryAcquire()) {
            send(x, 503, "text/plain; charset=utf-8", ("503 at most " + MAX_CLIENTS + " viewers at once").getBytes(StandardCharsets.UTF_8));
            return;
        }
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(CLIENT_QUEUE);
        boolean[] overflow = {false};
        Consumer<String> listener = line -> {
            if (!queue.offer(line)) {
                overflow[0] = true;
            }
        };
        try {
            x.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            x.sendResponseHeaders(200, 0);
            OutputStream o = x.getResponseBody();
            feed.attach(listener);
            while (!closed && !overflow[0]) {
                String line = queue.poll(PING_SECONDS, TimeUnit.SECONDS);
                if (line == null) {
                    o.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                } else {
                    StringBuilder b = new StringBuilder();
                    // JSON lines have no raw newlines; one data field each.
                    b.append("data: ").append(line.replace("\r", "").replace("\n", "\\n")).append("\n\n");
                    while ((line = queue.poll()) != null && b.length() < 256 * 1024) {
                        b.append("data: ").append(line.replace("\r", "").replace("\n", "\\n")).append("\n\n");
                    }
                    o.write(b.toString().getBytes(StandardCharsets.UTF_8));
                }
                o.flush();
            }
        } catch (IOException e) {
            // The browser went away.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            feed.detach(listener);
            clients.release();
        }
    }

    @Override
    public void close() {
        closed = true;
        server.stop(0);
        pool.shutdownNow();
    }
}
