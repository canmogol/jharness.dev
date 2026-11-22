package dev.jharness.testkit;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.agent.Session;
import dev.jharness.agent.TurnResult;
import dev.jharness.config.Settings;
import dev.jharness.events.Event;
import dev.jharness.net.http.HttpClients;
import dev.jharness.security.Prompter;
import dev.jharness.testkit.StubModelServer.Reply;

/** Spec 011 AC-07: every client the session builds goes through the egress proxy. */
class ProxyWiringTest {

    @TempDir
    Path tmp;
    StubModelServer stub;

    static final String HELLO = """
            data: {"choices":[{"index":0,"delta":{"content":"Hello through the proxy."},"finish_reason":"stop"}]}

            data: [DONE]

            """;

    @AfterEach
    void tearDown() {
        if (stub != null) {
            stub.close();
        }
    }

    private Session open(Path home) throws Exception {
        Settings s = Settings.parse("{\"model\":\"p\",\"models\":{\"p\":{\"provider\":\"openai-compatible\",\"baseUrl\":\"" + stub.baseUrl()
                + "/v1\",\"model\":\"m\"}}}", "t");
        return Session.open(home, Files.createDirectories(tmp.resolve("ws")), s, null, Session.Access.prompter(Prompter.none()));
    }

    @Test
    void aTurnGoesThroughTheProxyWithTheModelEndpointAllowed() throws Exception {
        stub = new StubModelServer();
        stub.enqueue(Reply.sse(HELLO));
        List<Event> events = new CopyOnWriteArrayList<>();
        TurnResult r;
        try (Session session = open(tmp.resolve("home"))) {
            session.bus().subscribe(events::add);
            r = session.loop().turn("hi", d -> {});
            assertNotNull(session.proxy());
        }
        assertEquals("done", r.stopReason(), r.toString());
        assertEquals("Hello through the proxy.", r.reply());
        assertEquals(1, stub.requests().size());
        assertNull(stub.requests().getFirst().header("proxy-authorization"), "credentials stay between client and proxy");
        Event allowed = events.stream().filter(e -> e.type().equals("network.allowed")).findFirst().orElseThrow();
        assertEquals("127.0.0.1", allowed.body().get("ip").asText());
        assertTrue(allowed.body().get("reason").asText().contains("model profile p"), allowed.body().toString());
    }

    @Test
    void httpsConnectCarriesTheCredentialsAndBlocksUnlistedHosts() throws Exception {
        stub = new StubModelServer();
        // An upstream that accepts and closes: the TLS handshake fails, but only after the proxy decided.
        ServerSocket upstream = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            while (!upstream.isClosed()) {
                try (Socket s = upstream.accept()) {
                    s.getInputStream().read();
                } catch (IOException e) {
                    return;
                }
            }
        });
        ServerSocket other = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Path home = tmp.resolve("home2");
        Files.createDirectories(home.resolve(".jharness"));
        Files.writeString(Session.generalNetworkRules(home), "allow 127.0.0.1:" + upstream.getLocalPort() + "\n");
        List<Event> events = new CopyOnWriteArrayList<>();
        try (Session session = open(home); upstream; other) {
            session.bus().subscribe(events::add);
            var client = HttpClients.create();
            for (int port : List.of(upstream.getLocalPort(), other.getLocalPort())) {
                HttpRequest req = HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + port + "/")).timeout(Duration.ofSeconds(5)).build();
                assertThrows(IOException.class, () -> client.send(req, HttpResponse.BodyHandlers.discarding()));
            }
        }
        List<Event> net = events.stream().filter(e -> e.type().startsWith("network.")).toList();
        assertTrue(net.stream().anyMatch(e -> e.type().equals("network.allowed") && e.body().get("port").asInt() == upstream.getLocalPort()),
                "the JDK client answered the proxy's 407 on CONNECT: " + net);
        Event blocked = net.stream().filter(e -> e.type().equals("network.blocked")).findFirst().orElseThrow();
        assertEquals(other.getLocalPort(), blocked.body().get("port").asInt());
        assertTrue(blocked.body().get("reason").asText().startsWith("built-in deny"), blocked.body().toString());
    }

    @Test
    void modelEndpointsBecomeAllowRules() throws Exception {
        Settings s = Settings.parse("{\"models\":{\"lan\":{\"provider\":\"openai-compatible\",\"baseUrl\":\"http://192.168.68.140:1234/v1\",\"model\":\"q\"},"
                + "\"c\":{\"provider\":\"anthropic\",\"model\":\"m\"},\"v6\":{\"provider\":\"openai-compatible\",\"baseUrl\":\"http://[::1]:11434/v1\",\"model\":\"q\"}}}",
                "t");
        stub = new StubModelServer();
        try (Session session = Session.open(tmp.resolve("home3"), Files.createDirectories(tmp.resolve("ws3")), s, "lan",
                Session.Access.prompter(Prompter.none()))) {
            List<String> lines = session.proxy().rules().automatic().stream().map(dev.jharness.net.Rule::line).sorted().toList();
            assertEquals(List.of("allow 192.168.68.140:1234", "allow [::1]:11434", "allow api.anthropic.com:443"), lines);
        }
    }
}
