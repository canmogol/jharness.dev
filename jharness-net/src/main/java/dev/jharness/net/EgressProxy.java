package dev.jharness.net;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import dev.jharness.config.ConfigException;
import dev.jharness.events.EventBus;
import dev.jharness.events.Json;
import dev.jharness.events.Origin;
import dev.jharness.security.Operation;
import dev.jharness.security.Prompter;

/**
 * The host egress proxy (D-025, spec 011): an HTTP/1.1 forward proxy on {@code 127.0.0.1} with
 * per-session credentials. {@code CONNECT} tunnels HTTPS without decrypting it; absolute-form
 * requests carry plain HTTP, one request per client connection. Every resolved address is checked
 * against the rules and the connection is pinned to a checked address.
 */
public final class EgressProxy implements AutoCloseable {

    /** Turns a host name into addresses; tests replace the system resolver. */
    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /** Re-reads the rules files (after {@code /network} edits or an "always" answer). */
    @FunctionalInterface
    public interface RulesSource {
        NetworkRules load() throws ConfigException;
    }

    static final int MAX_HEAD = 16 * 1024;
    static final int CONNECT_TIMEOUT_MS = 10_000;
    static final int HEAD_TIMEOUT_MS = 30_000;
    public static final String USER = "jharness";

    private final ServerSocket server;
    private final RulesSource source;
    private volatile NetworkRules rules;
    private final Prompter prompter;
    private final EventBus bus;
    private final Supplier<Origin> origin;
    private final Path projectRules;
    private final Resolver resolver;
    private final String token;
    private final byte[] expected;
    private final Set<String> sessionAllows = ConcurrentHashMap.newKeySet();
    private final Object asking = new Object();
    private final Thread acceptor;
    private volatile ServerSocketChannel unix;
    private volatile Path unixPath;
    private volatile boolean closed;

    private EgressProxy(ServerSocket server, RulesSource source, NetworkRules rules, Prompter prompter, EventBus bus,
            Supplier<Origin> origin, Path projectRules, Resolver resolver) {
        this.server = server;
        this.source = source;
        this.rules = rules;
        this.prompter = prompter != null ? prompter : Prompter.none();
        this.bus = bus;
        this.origin = origin;
        this.projectRules = projectRules;
        this.resolver = resolver;
        byte[] t = new byte[32];
        new SecureRandom().nextBytes(t);
        this.token = HexFormat.of().formatHex(t);
        this.expected = ("Basic " + Base64.getEncoder().encodeToString((USER + ":" + token).getBytes(StandardCharsets.UTF_8)))
                .getBytes(StandardCharsets.UTF_8);
        this.acceptor = Thread.ofVirtual().name("egress-proxy").start(this::acceptLoop);
    }

    /**
     * @param projectRules the project's {@code .jharness/network.rules}, where "always" answers go (D-047)
     */
    public static EgressProxy start(RulesSource source, Prompter prompter, EventBus bus, Supplier<Origin> origin, Path projectRules)
            throws IOException, ConfigException {
        return start(source, prompter, bus, origin, projectRules, InetAddress::getAllByName);
    }

    public static EgressProxy start(RulesSource source, Prompter prompter, EventBus bus, Supplier<Origin> origin, Path projectRules,
            Resolver resolver) throws IOException, ConfigException {
        NetworkRules r = source.load();
        ServerSocket s = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        return new EgressProxy(s, source, r, prompter, bus, origin, projectRules, resolver);
    }

    public InetSocketAddress address() {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort());
    }

    public PasswordAuthentication credentials() {
        return new PasswordAuthentication(USER, token.toCharArray());
    }

    public NetworkRules rules() {
        return rules;
    }

    /** {@code host:port} pairs allowed for this session by a "once" answer. */
    public Set<String> sessionAllows() {
        return Set.copyOf(sessionAllows);
    }

    public void reload() throws ConfigException {
        rules = source.load();
    }

    /**
     * Also accepts connections on a Unix socket and relays each one to the TCP listener, so it gets
     * the same credentials and rules. The Linux sandbox reaches the proxy only this way (D-049).
     */
    public synchronized void serveUnix(Path socket) throws IOException {
        if (unix != null) {
            throw new IllegalStateException("already serving " + unixPath);
        }
        Files.deleteIfExists(socket);
        ServerSocketChannel ch = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        ch.bind(UnixDomainSocketAddress.of(socket));
        unix = ch;
        unixPath = socket;
        Thread.ofVirtual().name("egress-proxy-unix").start(() -> {
            while (!closed) {
                SocketChannel c;
                try {
                    c = ch.accept();
                } catch (IOException e) {
                    return;
                }
                Thread.ofVirtual().name("egress-proxy-unix-conn").start(() -> relay(c));
            }
        });
    }

    private void relay(SocketChannel c) {
        try (c; Socket up = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
            Thread back = Thread.ofVirtual().start(() -> {
                try {
                    InputStream in = up.getInputStream();
                    byte[] buf = new byte[16 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        ByteBuffer b = ByteBuffer.wrap(buf, 0, n);
                        while (b.hasRemaining()) {
                            c.write(b);
                        }
                    }
                } catch (IOException e) {
                    // either side closed
                } finally {
                    try {
                        c.shutdownOutput();
                    } catch (IOException e) {
                        // closed
                    }
                }
            });
            try {
                OutputStream out = up.getOutputStream();
                ByteBuffer b = ByteBuffer.allocate(16 * 1024);
                while (c.read(b) > 0) {
                    out.write(b.array(), 0, b.position());
                    out.flush();
                    b.clear();
                }
                up.shutdownOutput();
            } catch (IOException e) {
                // either side closed
            }
            back.join();
        } catch (IOException e) {
            // the TCP listener is closed
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException e) {
            // closing
        }
        ServerSocketChannel u = unix;
        if (u != null) {
            try {
                u.close();
                Files.deleteIfExists(unixPath);
            } catch (IOException e) {
                // closing
            }
        }
        acceptor.interrupt();
    }

    private void acceptLoop() {
        while (!closed) {
            Socket c;
            try {
                c = server.accept();
            } catch (IOException e) {
                if (closed) {
                    return;
                }
                continue;
            }
            Thread.ofVirtual().name("egress-proxy-conn").start(() -> handle(c));
        }
    }

    // --- One client connection --------------------------------------------------------------

    private void handle(Socket client) {
        try (client) {
            client.setSoTimeout(HEAD_TIMEOUT_MS);
            InputStream in = new BufferedInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();
            Head head = Head.read(in);
            if (head == null) {
                return;
            }
            if (!authorized(head)) {
                respond(out, 407, "Proxy Authentication Required", "Proxy-Authenticate: Basic realm=\"jharness\"\r\n");
                return;
            }
            String[] start = head.start().split(" ");
            if (start.length != 3) {
                respond(out, 400, "Bad Request", "");
                return;
            }
            if (start[0].equals("CONNECT")) {
                connect(client, in, out, start[1]);
            } else if (start[1].toLowerCase(Locale.ROOT).startsWith("http://")) {
                plain(client, in, out, head, start[0], start[1], start[2]);
            } else {
                respond(out, 400, "Bad Request", "");
            }
        } catch (IOException e) {
            // the client or upstream went away
        }
    }

    private boolean authorized(Head head) {
        String v = head.header("proxy-authorization");
        return v != null && MessageDigest.isEqual(v.strip().getBytes(StandardCharsets.UTF_8), expected);
    }

    private void connect(Socket client, InputStream in, OutputStream out, String authority) throws IOException {
        HostPort hp = HostPort.parse(authority, -1);
        if (hp == null) {
            respond(out, 400, "Bad Request", "");
            return;
        }
        InetAddress ip = check(hp.host(), hp.port());
        if (ip == null) {
            respond(out, 403, "Forbidden", DENIED_HEADER);
            return;
        }
        Socket up = new Socket();
        try (up) {
            up.connect(new InetSocketAddress(ip, hp.port()), CONNECT_TIMEOUT_MS);
            out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            client.setSoTimeout(0);
            Thread toUp = Thread.ofVirtual().start(() -> pipe(in, up));
            pipe(up.getInputStream(), client);
            toUp.join(1000);
        } catch (IOException e) {
            if (!client.isClosed()) {
                respond(out, 502, "Bad Gateway", "");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void plain(Socket client, InputStream in, OutputStream out, Head head, String method, String target, String version)
            throws IOException {
        URI u;
        try {
            u = URI.create(target);
        } catch (IllegalArgumentException e) {
            respond(out, 400, "Bad Request", "");
            return;
        }
        if (u.getHost() == null) {
            respond(out, 400, "Bad Request", "");
            return;
        }
        if (head.header("transfer-encoding") != null) {
            respond(out, 411, "Length Required", "");
            return;
        }
        long length = 0;
        String cl = head.header("content-length");
        if (cl != null) {
            try {
                length = Long.parseLong(cl.strip());
            } catch (NumberFormatException e) {
                respond(out, 400, "Bad Request", "");
                return;
            }
        }
        String host = u.getHost().startsWith("[") ? u.getHost().substring(1, u.getHost().length() - 1) : u.getHost();
        int port = u.getPort() < 0 ? 80 : u.getPort();
        InetAddress ip = check(host, port);
        if (ip == null) {
            respond(out, 403, "Forbidden", DENIED_HEADER);
            return;
        }
        String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
        if (u.getRawQuery() != null) {
            path += "?" + u.getRawQuery();
        }
        Socket up = new Socket();
        try (up) {
            up.connect(new InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS);
            OutputStream uo = up.getOutputStream();
            StringBuilder req = new StringBuilder(method).append(' ').append(path).append(' ').append(version).append("\r\n");
            for (String[] h : head.headers()) {
                String n = h[0].toLowerCase(Locale.ROOT);
                if (n.startsWith("proxy-") || n.equals("connection") || n.equals("keep-alive")) {
                    continue;
                }
                req.append(h[0]).append(": ").append(h[1]).append("\r\n");
            }
            req.append("Connection: close\r\n\r\n");
            uo.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
            copy(in, uo, length);
            uo.flush();
            client.setSoTimeout(0);
            InputStream ui = new BufferedInputStream(up.getInputStream());
            Head resp = Head.read(ui);
            if (resp == null) {
                respond(out, 502, "Bad Gateway", "");
                return;
            }
            StringBuilder rh = new StringBuilder(resp.start()).append("\r\n");
            for (String[] h : resp.headers()) {
                String n = h[0].toLowerCase(Locale.ROOT);
                if (n.equals("connection") || n.equals("keep-alive")) {
                    continue;
                }
                rh.append(h[0]).append(": ").append(h[1]).append("\r\n");
            }
            rh.append("Connection: close\r\n\r\n");
            out.write(rh.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            ui.transferTo(new FlushingStream(out));
            out.flush();
        } catch (IOException e) {
            if (!client.isClosed()) {
                try {
                    respond(out, 502, "Bad Gateway", "");
                } catch (IOException ignored) {
                    // the client went away too
                }
            }
        }
    }

    // --- Decisions --------------------------------------------------------------------------

    /** The checked address to connect to, or null when blocked (events emitted either way). */
    InetAddress check(String host, int port) {
        InetAddress[] ips;
        try {
            ips = resolver.resolve(host);
        } catch (UnknownHostException e) {
            blocked(host, port, null, "cannot resolve " + host);
            return null;
        }
        if (ips == null || ips.length == 0) {
            blocked(host, port, null, "cannot resolve " + host);
            return null;
        }
        NetworkRules r = rules;
        boolean ask = false;
        String reason = null;
        for (InetAddress ip : ips) {
            NetworkRules.Decision d = r.decide(host, port, ip);
            switch (d.action()) {
                case DENY -> {
                    blocked(host, port, ip, d.reason());
                    return null;
                }
                case ASK -> ask = true;
                case ALLOW -> {
                    if (reason == null) {
                        reason = d.reason();
                    }
                }
            }
        }
        InetAddress pinned = ips[0];
        String key = key(host, port);
        if (ask) {
            reason = askUser(host, port, pinned, key);
            if (reason == null) {
                return null;
            }
        }
        bus.publish(origin.get(), "network.allowed", event(host, port, pinned, reason));
        return pinned;
    }

    /** @return the reason for an allow, or null when the user (or no one) said no */
    private String askUser(String host, int port, InetAddress ip, String key) {
        if (sessionAllows.contains(key)) {
            return "allowed for this session";
        }
        synchronized (asking) {
            if (sessionAllows.contains(key)) {
                return "allowed for this session";
            }
            bus.publish(origin.get(), "network.asked", event(host, port, ip, "no rule matched"));
            var detail = Json.object().put("host", host).put("port", port).put("ip", ip.getHostAddress());
            Prompter.Answer a = prompter.ask(new Operation("network", Operation.Kind.NETWORK, "connect " + key, detail));
            switch (a) {
                case ONCE -> {
                    sessionAllows.add(key);
                    return "allowed by the user for this session";
                }
                case ALWAYS -> {
                    sessionAllows.add(key);
                    try {
                        appendProjectRule("allow " + ruleTarget(host, port));
                    } catch (IOException | ConfigException e) {
                        return "allowed by the user for this session (could not save the rule: " + e.getMessage() + ")";
                    }
                    return "allowed by the user; saved to " + projectRules;
                }
                case UNATTENDED -> {
                    blocked(host, port, ip, "no one could answer (no turn was running, or a background subagent asked)");
                    return null;
                }
                default -> {
                    blocked(host, port, ip, "denied by the user");
                    return null;
                }
            }
        }
    }

    /** Appends a line to the project's rules file and reloads (D-047). */
    void appendProjectRule(String line) throws IOException, ConfigException {
        Files.createDirectories(projectRules.getParent());
        String prefix = Files.exists(projectRules) && Files.size(projectRules) > 0
                && !Files.readString(projectRules, StandardCharsets.UTF_8).endsWith("\n") ? "\n" : "";
        Files.writeString(projectRules, prefix + line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        reload();
    }

    static String key(String host, int port) {
        return (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
    }

    static String ruleTarget(String host, int port) {
        return key(host.toLowerCase(Locale.ROOT), port);
    }

    private void blocked(String host, int port, InetAddress ip, String reason) {
        bus.publish(origin.get(), "network.blocked", event(host, port, ip, reason));
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode event(String host, int port, InetAddress ip, String reason) {
        var e = Json.object().put("host", host).put("port", port);
        if (ip != null) {
            e.put("ip", ip.getHostAddress());
        }
        return e.put("reason", reason);
    }

    // --- Plumbing ---------------------------------------------------------------------------

    /** Marks the proxy's own refusals, so a client can tell them from a server's 403 (spec 016). */
    public static final String DENIED = "X-JHarness-Proxy";
    private static final String DENIED_HEADER = DENIED + ": denied\r\n";

    private static void respond(OutputStream out, int code, String text, String extra) throws IOException {
        out.write(("HTTP/1.1 " + code + " " + text + "\r\n" + extra + "Content-Length: 0\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static void copy(InputStream in, OutputStream out, long n) throws IOException {
        byte[] buf = new byte[8192];
        while (n > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, n));
            if (r < 0) {
                throw new IOException("request body ended early");
            }
            out.write(buf, 0, r);
            n -= r;
        }
    }

    /** Copies until EOF, then half-closes the other side so the peer sees the end. */
    private static void pipe(InputStream in, Socket to) {
        try {
            in.transferTo(new FlushingStream(to.getOutputStream()));
        } catch (IOException e) {
            // either side closed
        } finally {
            try {
                to.shutdownOutput();
            } catch (IOException | UnsupportedOperationException e) {
                // already closed
            }
        }
    }

    /** Flushes after every write, so streamed responses (SSE) reach the client as they arrive. */
    private static final class FlushingStream extends OutputStream {
        private final OutputStream out;

        FlushingStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            out.flush();
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            out.flush();
        }
    }

    record HostPort(String host, int port) {
        /** {@code host:port} or {@code [v6]:port}; {@code defaultPort} when the port is missing (-1 = required). */
        static HostPort parse(String s, int defaultPort) {
            String host;
            String port = null;
            if (s.startsWith("[")) {
                int c = s.indexOf(']');
                if (c < 0) {
                    return null;
                }
                host = s.substring(1, c);
                if (c + 1 < s.length()) {
                    if (s.charAt(c + 1) != ':') {
                        return null;
                    }
                    port = s.substring(c + 2);
                }
            } else {
                int c = s.lastIndexOf(':');
                if (c < 0) {
                    host = s;
                } else {
                    host = s.substring(0, c);
                    port = s.substring(c + 1);
                }
            }
            if (host.isEmpty()) {
                return null;
            }
            int p;
            if (port == null) {
                if (defaultPort < 0) {
                    return null;
                }
                p = defaultPort;
            } else {
                if (!port.matches("[0-9]{1,5}")) {
                    return null;
                }
                p = Integer.parseInt(port);
            }
            return p < 1 || p > 65535 ? null : new HostPort(host, p);
        }
    }

    /** A request or response head: the start line and headers, at most {@link #MAX_HEAD} bytes. */
    record Head(String start, List<String[]> headers) {

        String header(String name) {
            for (String[] h : headers) {
                if (h[0].equalsIgnoreCase(name)) {
                    return h[1];
                }
            }
            return null;
        }

        /** @return null on EOF before any byte; throws when the head is too long or malformed */
        static Head read(InputStream in) throws IOException {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            int prev3 = -1;
            int prev2 = -1;
            int prev1 = -1;
            while (true) {
                int b = in.read();
                if (b < 0) {
                    if (buf.size() == 0) {
                        return null;
                    }
                    throw new SocketException("connection closed inside the head");
                }
                buf.write(b);
                if (buf.size() > MAX_HEAD) {
                    throw new SocketException("head over " + MAX_HEAD + " bytes");
                }
                if (prev3 == '\r' && prev2 == '\n' && prev1 == '\r' && b == '\n') {
                    break;
                }
                prev3 = prev2;
                prev2 = prev1;
                prev1 = b;
            }
            String[] lines = buf.toString(StandardCharsets.ISO_8859_1).split("\r\n");
            List<String[]> headers = new ArrayList<>();
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].isEmpty()) {
                    continue;
                }
                int c = lines[i].indexOf(':');
                if (c <= 0) {
                    throw new SocketException("bad header line");
                }
                headers.add(new String[] {lines[i].substring(0, c).strip(), lines[i].substring(c + 1).strip()});
            }
            return new Head(lines[0], headers);
        }
    }
}
