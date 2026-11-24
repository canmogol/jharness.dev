package dev.jharness.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import dev.jharness.events.Json;
import dev.jharness.net.http.HttpClients;

/** Spec 037: the MCP client over stdio pipes and streamable HTTP, against small in-process servers. */
class McpClientTest {

    static final Duration T = Duration.ofSeconds(10);

    /** A server's answer to one request, or null for none. */
    static JsonNode answer(JsonNode m) {
        String method = m.path("method").asText();
        ObjectNode r = Json.object().put("jsonrpc", "2.0");
        r.set("id", m.get("id"));
        switch (method) {
            case "initialize" -> r.putObject("result").put("protocolVersion", McpClient.PROTOCOL).putObject("serverInfo").put("name", "fake");
            case "tools/list" -> {
                var res = r.putObject("result");
                var tools = res.putArray("tools");
                if (!m.path("params").has("cursor")) {
                    tools.addObject().put("name", "echo").put("description", "Echoes text");
                    res.put("nextCursor", "page2");
                } else {
                    tools.addObject().put("name", "fail").put("description", "Always fails");
                }
            }
            case "tools/call" -> {
                String name = m.path("params").path("name").asText();
                if (name.equals("echo")) {
                    r.putObject("result").putArray("content").addObject().put("type", "text").put("text", m.path("params").path("arguments").path("text").asText());
                } else {
                    r.putObject("error").put("code", -32000).put("message", "it failed");
                }
            }
            default -> {
                return null;
            }
        }
        return r;
    }

    @Test
    void stdio() throws Exception {
        PipedOutputStream toClient = new PipedOutputStream();
        PipedInputStream clientIn = new PipedInputStream(toClient, 1 << 16);
        PipedInputStream serverIn = new PipedInputStream(1 << 16);
        PipedOutputStream clientOut = new PipedOutputStream(serverIn);
        List<String> seen = new CopyOnWriteArrayList<>();
        Thread server = Thread.ofPlatform().daemon().start(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(serverIn, StandardCharsets.UTF_8)); OutputStream w = toClient) {
                String line;
                while ((line = r.readLine()) != null) {
                    JsonNode m = Json.parse(line);
                    seen.add(m.path("method").asText(m.has("result") ? "reply" : "?"));
                    if (m.path("method").asText().equals("tools/list") && !m.path("params").has("cursor")) {
                        // A server-side ping and a notification before the answer.
                        w.write("{\"jsonrpc\":\"2.0\",\"id\":\"p1\",\"method\":\"ping\"}\n{\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\"}\nnot json\n".getBytes());
                    }
                    if (m.path("method").asText().equals("tools/call") && m.path("params").path("name").asText().equals("bye")) {
                        return;
                    }
                    JsonNode a = m.has("id") && m.has("method") ? answer(m) : null;
                    if (a != null) {
                        w.write((Json.write(a) + "\n").getBytes(StandardCharsets.UTF_8));
                        w.flush();
                    }
                    if (m.path("method").asText().equals("tools/call") && m.path("params").path("name").asText().equals("bye")) {
                        return;
                    }
                }
            } catch (IOException e) {
                // closed
            }
        });
        McpClient c = McpClient.stdio(clientIn, clientOut, "fake");
        assertEquals("fake", c.initialize(T).path("serverInfo").path("name").asText());
        var tools = c.listTools(T);
        assertEquals(List.of("echo", "fail"), List.of(tools.get(0).get("name").asText(), tools.get(1).get("name").asText()), "pages followed");
        assertEquals("hi", McpClient.texts(c.callTool("echo", Json.parse("{\"text\":\"hi\"}"), T)).getFirst());
        assertTrue(assertThrows(McpClient.McpException.class, () -> c.callTool("fail", null, T)).getMessage().contains("-32000: it failed"));
        assertTrue(seen.contains("notifications/initialized"), seen.toString());
        assertTrue(seen.contains("reply"), "the server's ping was answered: " + seen);
        var e = assertThrows(McpClient.McpException.class, () -> c.callTool("bye", null, Duration.ofSeconds(5)));
        assertTrue(e.getMessage().contains("closed the connection") || e.getMessage().contains("took longer"), e.getMessage());
        c.close();
        server.join(2000);
    }

    @Test
    void streamableHttpWithJsonAndEventStreamAnswers() throws Exception {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> sessions = new CopyOnWriteArrayList<>();
        http.createContext("/mcp", x -> {
            String sid = x.getRequestHeaders().getFirst("Mcp-Session-Id");
            sessions.add(String.valueOf(sid) + " " + x.getRequestHeaders().getFirst("Authorization") + " " + x.getRequestMethod());
            if (x.getRequestMethod().equals("DELETE")) {
                x.sendResponseHeaders(204, -1);
                x.close();
                return;
            }
            JsonNode m = Json.parse(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (!m.has("id")) {
                x.sendResponseHeaders(202, -1);
                x.close();
                return;
            }
            JsonNode a = answer(m);
            byte[] body;
            if (m.path("method").asText().equals("tools/call")) {
                x.getResponseHeaders().add("Content-Type", "text/event-stream");
                body = ("event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\nevent: message\ndata: " + Json.write(a) + "\n\n")
                        .getBytes(StandardCharsets.UTF_8);
            } else {
                x.getResponseHeaders().add("Content-Type", "application/json");
                body = Json.write(a).getBytes(StandardCharsets.UTF_8);
            }
            if (m.path("method").asText().equals("initialize")) {
                x.getResponseHeaders().add("Mcp-Session-Id", "s-123");
            }
            x.sendResponseHeaders(200, body.length);
            x.getResponseBody().write(body);
            x.close();
        });
        http.start();
        try {
            McpClient c = McpClient.http(HttpClients.create(), URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/mcp"), "tok");
            c.initialize(T);
            assertEquals(2, c.listTools(T).size());
            assertEquals("over sse", McpClient.texts(c.callTool("echo", Json.parse("{\"text\":\"over sse\"}"), T)).getFirst());
            c.close();
            assertEquals("null Bearer tok POST", sessions.getFirst(), "the first request has no session");
            assertTrue(sessions.subList(1, sessions.size()).stream().allMatch(s -> s.startsWith("s-123 Bearer tok")), sessions.toString());
            assertTrue(sessions.getLast().endsWith("DELETE"), "the session is ended on close");
        } finally {
            http.stop(0);
        }
    }
}
