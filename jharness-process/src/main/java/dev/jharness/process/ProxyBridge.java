package dev.jharness.process;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Runs inside the Linux sandbox's private network namespace (D-049): listens on
 * {@code 127.0.0.1:<port>}, forwards every connection to the egress proxy's Unix socket, and starts
 * the tool as its child. It is the JHarness binary itself ({@code jharness __proxy-bridge}), so no
 * other program is shipped. Arguments: {@code <socket> <port> -- <argv...>}, given by
 * {@link ChildSandbox}; the tool's exit code is the bridge's.
 */
public final class ProxyBridge {

    public static final String FLAG = "__proxy-bridge";

    private ProxyBridge() {}

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** @return the tool's exit code, or 125 when the bridge itself fails */
    public static int run(String[] args) {
        if (args.length < 4 || !args[2].equals("--")) {
            System.err.println("jharness: usage: " + FLAG + " <socket> <port> -- <command...>");
            return 125;
        }
        Path socket = Path.of(args[0]);
        int port;
        try {
            port = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            System.err.println("jharness: bad proxy port " + args[1]);
            return 125;
        }
        List<String> argv = Arrays.asList(args).subList(3, args.length);
        ServerSocket server;
        try {
            server = new ServerSocket(port, 50, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
        } catch (IOException e) {
            System.err.println("jharness: proxy bridge cannot listen on 127.0.0.1:" + port + ": " + e.getMessage());
            return 125;
        }
        Thread.ofVirtual().name("proxy-bridge").start(() -> accept(server, socket));
        try {
            Process p = new ProcessBuilder(argv).inheritIO().start();
            return p.waitFor();
        } catch (IOException e) {
            System.err.println("jharness: cannot start " + argv.getFirst() + ": " + e.getMessage());
            return 127;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 125;
        }
    }

    private static void accept(ServerSocket server, Path socket) {
        while (true) {
            Socket c;
            try {
                c = server.accept();
            } catch (IOException e) {
                return;
            }
            Thread.ofVirtual().start(() -> forward(c, socket));
        }
    }

    private static void forward(Socket client, Path socket) {
        try (client; SocketChannel up = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            up.connect(UnixDomainSocketAddress.of(socket));
            Thread back = Thread.ofVirtual().start(() -> {
                try {
                    OutputStream out = client.getOutputStream();
                    ByteBuffer b = ByteBuffer.allocate(16 * 1024);
                    while (up.read(b) > 0) {
                        out.write(b.array(), 0, b.position());
                        out.flush();
                        b.clear();
                    }
                } catch (IOException e) {
                    // either side closed
                } finally {
                    try {
                        client.shutdownOutput();
                    } catch (IOException e) {
                        // closed
                    }
                }
            });
            try {
                InputStream in = client.getInputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    ByteBuffer b = ByteBuffer.wrap(buf, 0, n);
                    while (b.hasRemaining()) {
                        up.write(b);
                    }
                }
                up.shutdownOutput();
            } catch (IOException e) {
                // either side closed
            }
            back.join();
        } catch (IOException e) {
            // the proxy socket is gone; the client sees a closed connection
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
