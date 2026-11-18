package dev.jharness.testkit;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A local HTTP server on 127.0.0.1 that plays back queued responses, for testing the model
 * providers without a model. Test infrastructure only; never part of the product.
 */
public final class StubModelServer implements AutoCloseable {

    /** One queued response. */
    /** @param holdMs keep the connection open this long after the body, as a model that is still thinking */
    public record Reply(int status, String contentType, String body, long holdMs) {
        public static Reply sse(String body) {
            return new Reply(200, "text/event-stream", body, 0);
        }

        public static Reply json(int status, String body) {
            return new Reply(status, "application/json", body, 0);
        }

        public static Reply hanging(String body, long holdMs) {
            return new Reply(200, "text/event-stream", body, holdMs);
        }
    }

    /** What the client sent. */
    public record Request(String method, String path, Map<String, List<String>> headers, String body) {
        public String header(String name) {
            for (var e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue().getFirst();
                }
            }
            return null;
        }
    }

    private final HttpServer server;
    private final Deque<Reply> replies = new ArrayDeque<>();
    private final List<Request> requests = new ArrayList<>();

    public StubModelServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public synchronized StubModelServer enqueue(Reply r) {
        replies.add(r);
        return this;
    }

    public synchronized List<Request> requests() {
        return List.copyOf(requests);
    }

    private void handle(HttpExchange x) throws IOException {
        String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Reply r;
        synchronized (this) {
            requests.add(new Request(x.getRequestMethod(), x.getRequestURI().getPath(), Map.copyOf(x.getRequestHeaders()), body));
            r = replies.poll();
        }
        if (r == null) {
            r = Reply.json(500, "{\"error\":{\"message\":\"stub has no reply queued\"}}");
        }
        byte[] b = r.body().getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("content-type", r.contentType());
        x.sendResponseHeaders(r.status(), 0);
        try (OutputStream out = x.getResponseBody()) {
            out.write(b);
            out.flush();
            if (r.holdMs() > 0) {
                try {
                    Thread.sleep(r.holdMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } catch (IOException e) {
            // The client went away (cancelled); nothing to do.
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
