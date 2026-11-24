package dev.jharness.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.jharness.events.Json;

/**
 * A small MCP client (spec 037, D-023, D-076): JSON-RPC 2.0 over a stdio server's pipes or a streamable
 * HTTP endpoint. It speaks {@code initialize}, {@code tools/list} and {@code tools/call}; it answers a
 * server's {@code ping} and refuses its other requests.
 */
public final class McpClient implements AutoCloseable {

    public static final String PROTOCOL = "2025-06-18";
    /** Longest JSON message accepted from a server. */
    static final int MAX_MESSAGE = 8 << 20;

    /** One way to send messages to a server. */
    interface Transport extends AutoCloseable {
        JsonNode request(ObjectNode message, Duration timeout) throws IOException, TimeoutException, InterruptedException;

        void notify(ObjectNode message) throws IOException, InterruptedException;

        @Override
        void close();
    }

    /** A failed call: the server's JSON-RPC error, or a broken connection. */
    public static final class McpException extends Exception {
        public McpException(String message) {
            super(message);
        }
    }

    private final Transport transport;
    private final AtomicLong ids = new AtomicLong();
    private volatile JsonNode serverInfo;

    private McpClient(Transport transport) {
        this.transport = transport;
    }

    /** Over a stdio server's pipes: one JSON message per line. */
    public static McpClient stdio(InputStream fromServer, OutputStream toServer, String name) {
        return new McpClient(new Stdio(fromServer, toServer, name));
    }

    /** Over streamable HTTP; {@code token} is sent as a bearer token, or null. */
    public static McpClient http(HttpClient client, URI url, String token) {
        return new McpClient(new Http(client, url, token));
    }

    /** {@code initialize} then {@code notifications/initialized}. */
    public JsonNode initialize(Duration timeout) throws McpException {
        ObjectNode params = Json.object().put("protocolVersion", PROTOCOL);
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", "jharness").put("version", "0.1");
        JsonNode r = call("initialize", params, timeout);
        serverInfo = r;
        try {
            transport.notify(Json.object().put("jsonrpc", "2.0").put("method", "notifications/initialized"));
        } catch (IOException e) {
            throw new McpException("the server closed the connection: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("interrupted");
        }
        return r;
    }

    public JsonNode serverInfo() {
        return serverInfo;
    }

    /** Every tool, following {@code nextCursor}. */
    public ArrayNode listTools(Duration timeout) throws McpException {
        ArrayNode all = Json.array();
        String cursor = null;
        for (int page = 0; page < 50; page++) {
            ObjectNode p = Json.object();
            if (cursor != null) {
                p.put("cursor", cursor);
            }
            JsonNode r = call("tools/list", p, timeout);
            r.path("tools").forEach(all::add);
            cursor = r.path("nextCursor").isTextual() ? r.get("nextCursor").asText() : null;
            if (cursor == null) {
                break;
            }
        }
        return all;
    }

    /** {@code tools/call}: the result object ({@code content}, {@code isError}, {@code structuredContent}). */
    public JsonNode callTool(String name, JsonNode arguments, Duration timeout) throws McpException {
        ObjectNode p = Json.object().put("name", name);
        p.set("arguments", arguments == null || arguments.isNull() ? Json.object() : arguments);
        return call("tools/call", p, timeout);
    }

    private JsonNode call(String method, ObjectNode params, Duration timeout) throws McpException {
        long id = ids.incrementAndGet();
        ObjectNode m = Json.object().put("jsonrpc", "2.0").put("id", id).put("method", method);
        m.set("params", params);
        JsonNode r;
        try {
            r = transport.request(m, timeout);
        } catch (TimeoutException e) {
            throw new McpException(method + " took longer than " + timeout.toSeconds() + " s");
        } catch (IOException e) {
            throw new McpException("the server closed the connection: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("interrupted");
        }
        if (r.has("error")) {
            JsonNode e = r.get("error");
            throw new McpException("the server answered " + e.path("code").asInt() + ": " + e.path("message").asText());
        }
        return r.path("result");
    }

    @Override
    public void close() {
        transport.close();
    }

    /** Newline-delimited JSON over pipes; a reader thread matches responses to requests by id. */
    static final class Stdio implements Transport {
        private final OutputStream out;
        private final Map<Long, CompletableFuture<JsonNode>> waiting = new ConcurrentHashMap<>();
        private volatile IOException closed;

        Stdio(InputStream in, OutputStream out, String name) {
            this.out = out;
            Thread.ofPlatform().daemon().name("mcp-" + name).start(() -> read(in));
        }

        private void read(InputStream in) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isBlank() || line.length() > MAX_MESSAGE) {
                        continue;
                    }
                    JsonNode m;
                    try {
                        m = Json.parse(line);
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    handle(m);
                }
                closed = new IOException("the server's output ended");
            } catch (IOException e) {
                closed = e;
            }
            waiting.values().forEach(f -> f.completeExceptionally(closed));
            waiting.clear();
        }

        private void handle(JsonNode m) {
            if (m.has("method")) {
                if (m.has("id")) {
                    // A request from the server: ping is answered; nothing else is offered.
                    ObjectNode reply = Json.object().put("jsonrpc", "2.0");
                    reply.set("id", m.get("id"));
                    if (m.get("method").asText().equals("ping")) {
                        reply.putObject("result");
                    } else {
                        reply.putObject("error").put("code", -32601).put("message", "JHarness doesn't offer " + m.get("method").asText());
                    }
                    try {
                        send(reply);
                    } catch (IOException e) {
                        // The read loop sees the end.
                    }
                }
                return;
            }
            if (m.has("id") && m.get("id").canConvertToLong()) {
                CompletableFuture<JsonNode> f = waiting.remove(m.get("id").asLong());
                if (f != null) {
                    f.complete(m);
                }
            }
        }

        private synchronized void send(JsonNode m) throws IOException {
            out.write((Json.write(m) + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        @Override
        public JsonNode request(ObjectNode message, Duration timeout) throws IOException, TimeoutException, InterruptedException {
            if (closed != null) {
                throw closed;
            }
            CompletableFuture<JsonNode> f = new CompletableFuture<>();
            long id = message.get("id").asLong();
            waiting.put(id, f);
            send(message);
            try {
                return f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
            } finally {
                waiting.remove(id);
            }
        }

        @Override
        public void notify(ObjectNode message) throws IOException {
            send(message);
        }

        @Override
        public void close() {
            try {
                out.close();
            } catch (IOException e) {
                // Already closed.
            }
        }
    }

    /** Streamable HTTP: each message is a POST; the answer is JSON or a server-sent event stream. */
    static final class Http implements Transport {
        private final HttpClient client;
        private final URI url;
        private final String token;
        private volatile String session;
        private volatile boolean initialized;

        Http(HttpClient client, URI url, String token) {
            this.client = client;
            this.url = url;
            this.token = token;
        }

        private HttpRequest.Builder post(JsonNode m, Duration timeout) {
            HttpRequest.Builder b = HttpRequest.newBuilder(url).timeout(timeout)
                    .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(m)));
            if (token != null) {
                b.header("Authorization", "Bearer " + token);
            }
            if (session != null) {
                b.header("Mcp-Session-Id", session);
            }
            if (initialized) {
                b.header("MCP-Protocol-Version", PROTOCOL);
            }
            return b;
        }

        @Override
        public JsonNode request(ObjectNode message, Duration timeout) throws IOException, TimeoutException, InterruptedException {
            HttpResponse<InputStream> r;
            try {
                r = client.send(post(message, timeout).build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (java.net.http.HttpTimeoutException e) {
                throw new TimeoutException(e.getMessage());
            }
            r.headers().firstValue("Mcp-Session-Id").ifPresent(s -> session = s);
            if (message.path("method").asText().equals("initialize")) {
                initialized = true;
            }
            if (r.statusCode() / 100 != 2) {
                r.body().close();
                throw new IOException("HTTP " + r.statusCode() + " from " + url);
            }
            String type = r.headers().firstValue("Content-Type").orElse("");
            try (InputStream in = r.body()) {
                if (type.startsWith("text/event-stream")) {
                    return events(in, message.get("id").asLong());
                }
                byte[] body = in.readNBytes(MAX_MESSAGE);
                return Json.parse(new String(body, StandardCharsets.UTF_8));
            }
        }

        /** The first event whose data is the response to {@code id}. */
        private static JsonNode events(InputStream in, long id) throws IOException {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("data:")) {
                    data.append(line.substring(5).strip());
                } else if (line.isEmpty() && !data.isEmpty()) {
                    JsonNode m = Json.parse(data.toString());
                    data.setLength(0);
                    if (!m.has("method") && m.path("id").asLong(-1) == id) {
                        return m;
                    }
                }
            }
            if (!data.isEmpty()) {
                JsonNode m = Json.parse(data.toString());
                if (m.path("id").asLong(-1) == id) {
                    return m;
                }
            }
            throw new IOException("the event stream ended without an answer");
        }

        @Override
        public void notify(ObjectNode message) throws IOException, InterruptedException {
            HttpResponse<Void> r = client.send(post(message, Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.discarding());
            if (r.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + r.statusCode() + " from " + url);
            }
        }

        @Override
        public void close() {
            String s = session;
            if (s != null) {
                try {
                    HttpRequest.Builder b = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(5)).header("Mcp-Session-Id", s).DELETE();
                    if (token != null) {
                        b.header("Authorization", "Bearer " + token);
                    }
                    client.send(b.build(), HttpResponse.BodyHandlers.discarding());
                } catch (IOException | RuntimeException e) {
                    // The server forgets the session on its own.
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    static List<String> texts(JsonNode result) {
        List<String> out = new java.util.ArrayList<>();
        result.path("content").forEach(c -> {
            if (c.path("type").asText().equals("text")) {
                out.add(c.path("text").asText());
            }
        });
        return out;
    }
}
