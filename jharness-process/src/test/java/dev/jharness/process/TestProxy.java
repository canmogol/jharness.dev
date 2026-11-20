package dev.jharness.process;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import dev.jharness.events.EventBus;
import dev.jharness.events.Origin;
import dev.jharness.net.EgressProxy;
import dev.jharness.net.NetworkRules;
import dev.jharness.net.Rule;
import dev.jharness.net.RulesFile;

/**
 * A stub HTTP server, the real egress proxy allowing only that server, and the OS sandbox with a
 * route to the proxy (spec 013). {@link #sandbox} is unavailable when this OS has no sandbox.
 */
final class TestProxy implements AutoCloseable {

    static final String BODY = "hello from stub";

    final ServerSocket stub;
    final EgressProxy proxy;
    final ChildSandbox sandbox;
    final Path sessionDir;

    TestProxy(Path workspace, EventBus bus, Path tmp) throws Exception {
        stub = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(this::serve);
        List<Rule> allow = List.of(RulesFile.rule(Rule.Action.ALLOW, "127.0.0.1:" + stub.getLocalPort(), "test"));
        proxy = EgressProxy.start(() -> new NetworkRules(RulesFile.empty(tmp.resolve("g")), RulesFile.empty(tmp.resolve("p")), true, allow),
                null, bus, () -> Origin.main(1), tmp.resolve("p"));
        // Unix socket paths are short (104 bytes on macOS); @TempDir paths can be long.
        sessionDir = Files.createTempDirectory("jh-net-");
        Path sock = sessionDir.resolve("proxy.sock");
        ChildSandbox base = TestSandboxes.in(workspace);
        if (base.mechanism() == ChildSandbox.Mechanism.BUBBLEWRAP && base.available()) {
            proxy.serveUnix(sock);
        }
        var cred = proxy.credentials();
        sandbox = base.withProxy(new ChildSandbox.ProxyRoute(proxy.address().getPort(), sock, cred.getUserName(), new String(cred.getPassword())),
                ChildSandbox.selfBridgeCommand());
    }

    int stubPort() {
        return stub.getLocalPort();
    }

    String password() {
        return new String(proxy.credentials().getPassword());
    }

    String proxyUrl() {
        return "http://" + EgressProxy.USER + ":" + password() + "@127.0.0.1:" + proxy.address().getPort();
    }

    /** Answers every request with {@link #BODY}. */
    private void serve() {
        while (!stub.isClosed()) {
            try (Socket c = stub.accept()) {
                InputStream in = c.getInputStream();
                StringBuilder head = new StringBuilder();
                int b;
                while ((b = in.read()) >= 0) {
                    head.append((char) b);
                    if (head.toString().endsWith("\r\n\r\n")) {
                        break;
                    }
                }
                OutputStream out = c.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + BODY.length() + "\r\nConnection: close\r\n\r\n" + BODY)
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } catch (IOException e) {
                // closed
            }
        }
    }

    @Override
    public void close() throws IOException {
        proxy.close();
        stub.close();
    }
}
