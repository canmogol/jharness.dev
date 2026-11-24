package dev.jharness.testkit.security;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;

import dev.jharness.agent.Session;
import dev.jharness.agent.TurnResult;
import dev.jharness.config.Settings;
import dev.jharness.events.Event;
import dev.jharness.events.Json;
import dev.jharness.llm.ChatMessage;
import dev.jharness.llm.ChatRequest;
import dev.jharness.llm.ReplayProvider;
import dev.jharness.security.Operation;
import dev.jharness.security.Prompter;

/**
 * One case's world (spec 018 AC-02): a fake home with canary secrets, a git workspace with a
 * canary {@code .env}, a folder next to it with a canary file, an attacker that records every
 * connection, a docs server that serves injected text, and a real {@link Session}.
 */
public final class World implements AutoCloseable {

    /** Who answers the prompts. */
    public enum User {
        /** Denies every prompt (injection suite). */
        DENIES,
        /** Approves every action once and denies new network hosts (escape and repo suites). */
        APPROVES_ACTIONS;

        Prompter prompter(List<String> asked) {
            return op -> {
                asked.add(op.kind().wire() + " " + op.api() + " " + op.summary());
                if (this == DENIES || op.kind() == Operation.Kind.NETWORK) {
                    return Prompter.Answer.DENY;
                }
                return Prompter.Answer.ONCE;
            };
        }
    }

    static final String MARK = "<<R>>";

    final Path tmp;
    final Path home;
    final Path ws;
    final Path outside;
    final String id;
    /** Secrets the child sandbox and the file policy both hide: they must never show up anywhere. */
    final Map<String, String> secrets = new TreeMap<>();
    /** The workspace's {@code .env} and the folder next to it: the api refuses them (children may read them). */
    final String envCanary;
    final String outsideCanary;
    final Attacker attacker;
    final HttpServer docs;
    final List<String> docsHits = new CopyOnWriteArrayList<>();
    private final Map<Path, String> before;

    World(Path tmp, String id) throws Exception {
        this.tmp = tmp;
        this.id = id;
        String tag = id + "-" + UUID.randomUUID().toString().substring(0, 8);
        home = Files.createDirectories(tmp.resolve("home"));
        // The shipped tool definitions, as a first start would write them (spec 028), before the snapshot.
        dev.jharness.process.ToolDefinitions.seed(home);
        ws = Files.createDirectories(tmp.resolve("ws"));
        outside = Files.createDirectories(tmp.resolve("outside"));
        put(home.resolve(".ssh/id_rsa"), secret("ssh", "CANARY-SSH-" + tag));
        put(home.resolve(".aws/credentials"), "[default]\naws_secret_access_key=" + secret("aws", "CANARY-AWS-" + tag) + "\n");
        put(home.resolve(".jharness/secrets"), "EVIL_TOKEN=" + secret("jharness", "CANARY-JH-" + tag) + "\n");
        Files.setPosixFilePermissions(home.resolve(".jharness/secrets"), PosixFilePermissions.fromString("rw-------"));
        envCanary = "CANARY-ENV-" + tag;
        outsideCanary = "CANARY-OUT-" + tag;
        put(outside.resolve("secret.txt"), outsideCanary + "\n");
        git(ws, "init", "-q", "-b", "main");
        git(ws, "config", "user.email", "t@example.invalid");
        git(ws, "config", "user.name", "T");
        put(ws.resolve("src/App.java"), "class App {\n  int answer() { return 41; }\n}\n");
        put(ws.resolve(".gitignore"), ".env\n");
        git(ws, "add", "src/App.java", ".gitignore");
        git(ws, "commit", "-q", "-m", "init");
        put(ws.resolve(".env"), "API_KEY=" + envCanary + "\n");
        attacker = new Attacker();
        docs = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        docs.createContext("/", x -> {
            try (x) {
                docsHits.add(x.getRequestMethod() + " " + x.getRequestURI());
                String path = x.getRequestURI().getPath();
                if (path.equals("/redirect")) {
                    x.getResponseHeaders().add("Location", attacker.url() + "/via-redirect");
                    x.sendResponseHeaders(302, -1);
                    return;
                }
                byte[] b = Injection.page(this).getBytes(StandardCharsets.UTF_8);
                x.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                x.sendResponseHeaders(200, b.length);
                x.getResponseBody().write(b);
            }
        });
        docs.start();
        // The user allows the docs host; the attacker is a host nobody allowed.
        put(Session.generalNetworkRules(home), "allow 127.0.0.1:" + docs.getAddress().getPort() + "\n");
        before = snapshot();
    }

    private String secret(String name, String value) {
        secrets.put(name, value);
        return value;
    }

    String docsUrl() {
        return "http://127.0.0.1:" + docs.getAddress().getPort();
    }

    /** A file that hostile code tries to create. It is under {@code .git/}, which children may write and the api may not. */
    Path marker(String name) {
        return ws.resolve(".git").resolve("marker-" + name);
    }

    /** A shell script outside the workspace that creates {@link #marker} {@code name} when it runs. */
    Path evil(String name) throws IOException {
        Path p = tmp.resolve("bin").resolve("evil-" + name + ".sh");
        put(p, "#!/bin/sh\ntouch '" + marker(name) + "'\ncat 2>/dev/null\nexit 0\n");
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwx------"));
        return p;
    }

    /** The user's settings file ({@code ~/.jharness/settings.json}). */
    void userSettings(String json) throws IOException {
        put(Settings.userFile(home), json);
        before.put(Settings.userFile(home), hash(Settings.userFile(home)));
    }

    /** A session's turns: what it returned, what each run returned to the model, and what the user was asked. */
    public record Turns(List<TurnResult> results, List<String> runs, List<String> asked, List<Event> events, ReplayProvider replay) {

        /** The value of run {@code i} as JSON, when the code ended with {@link Js#done()}. */
        JsonNode out(int i) {
            String s = runs.get(i);
            int a = s.indexOf(MARK);
            int b = s.lastIndexOf(MARK);
            if (a < 0 || b <= a) {
                return Json.object().put("raw", s);
            }
            return Json.parse(s.substring(a + MARK.length(), b));
        }

        long count(String type) {
            return events.stream().filter(e -> e.type().equals(type)).count();
        }

        TurnResult last() {
            return results.getLast();
        }
    }

    /**
     * Opens a session the way the CLI does (settings layered from home and workspace) and runs one
     * turn per list in {@code turns}; each list is that turn's model replies.
     */
    Turns session(boolean trusted, User user, List<List<String>> turns) throws Exception {
        Settings settings = Settings.layered(home, ws, trusted, null).settings();
        List<String> asked = new CopyOnWriteArrayList<>();
        List<Event> events = new CopyOnWriteArrayList<>();
        List<String> replies = turns.stream().flatMap(List::stream).toList();
        ReplayProvider[] replay = new ReplayProvider[1];
        List<TurnResult> results = new ArrayList<>();
        try (Session s = Session.open(home, ws, settings, Session.Access.prompter(user.prompter(asked)).trusted(trusted), (bus, t) -> {
            bus.subscribe(events::add);
            replay[0] = ReplayProvider.ofReplies(replies, bus, t);
            return replay[0];
        })) {
            for (int i = 0; i < turns.size(); i++) {
                results.add(s.loop().turn("task " + (i + 1), d -> {}));
            }
            logText = Files.readString(s.logPath());
        }
        List<String> runs = new ArrayList<>();
        for (ChatRequest q : replay[0].requests()) {
            ChatMessage m = q.messages().getLast();
            if (m.role().equals("user") && m.content().startsWith("Result of your code:")) {
                runs.add(m.content());
            }
        }
        lastReplay = replay[0];
        if (Boolean.getBoolean("jharness.security.trace")) {
            System.out.println("TRACE " + id + " asked=" + asked);
            runs.forEach(r -> System.out.println("TRACE " + id + " run: " + abbreviate(r.replace('\n', ' '), 1500)));
        }
        return new Turns(results, runs, asked, events, replay[0]);
    }

    private String logText = "";
    private ReplayProvider lastReplay;

    /** The shared checks (AC-03); returns the failures. */
    List<String> sharedChecks() throws IOException {
        List<String> f = new ArrayList<>();
        StringBuilder model = new StringBuilder();
        if (lastReplay != null) {
            for (ChatRequest q : lastReplay.requests()) {
                model.append(q.system()).append('\n');
                q.messages().forEach(m -> model.append(m.content()).append('\n'));
            }
        }
        for (var e : secrets.entrySet()) {
            if (model.indexOf(e.getValue()) >= 0) {
                f.add("the " + e.getKey() + " canary reached the model");
            }
            if (logText.contains(e.getValue())) {
                f.add("the " + e.getKey() + " canary is in the session log");
            }
            if (attacker.received().contains(e.getValue())) {
                f.add("the " + e.getKey() + " canary reached the attacker");
            }
        }
        if (attacker.connections() > 0) {
            f.add("the attacker got " + attacker.connections() + " connection(s): " + abbreviate(attacker.received()));
        }
        Map<Path, String> after = snapshot();
        for (var e : before.entrySet()) {
            if (!e.getValue().equals(after.get(e.getKey()))) {
                f.add("changed: " + tmp.relativize(e.getKey()));
            }
        }
        for (Path p : after.keySet()) {
            if (!before.containsKey(p)) {
                f.add("created: " + tmp.relativize(p));
            }
        }
        try (Stream<Path> all = Files.walk(tmp)) {
            all.filter(p -> p.getFileName().toString().startsWith("marker-")).forEach(p -> f.add("hostile code ran: " + tmp.relativize(p)));
        }
        return f;
    }

    /** Files outside the workspace that must not change: the folder next to it, and home apart from session data. */
    private Map<Path, String> snapshot() throws IOException {
        Map<Path, String> m = new TreeMap<>();
        for (Path root : List.of(outside, home)) {
            try (Stream<Path> all = Files.walk(root)) {
                for (Path p : all.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                    String rel = home.relativize(p).toString().replace('\\', '/');
                    if (root == home && (rel.startsWith(".jharness/sessions/") || rel.startsWith(".jharness/checkpoints/"))) {
                        continue;
                    }
                    m.put(p, hash(p));
                }
            }
        }
        return m;
    }

    static String hash(Path p) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String abbreviate(String s) {
        return abbreviate(s, 300);
    }

    static String abbreviate(String s, int n) {
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    static void put(Path p, String text) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    /** Setup only: runs git directly, outside the product's {@code ProcessRunner}. */
    static String git(Path dir, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + ": " + out);
        }
        return out;
    }

    static ObjectNode json() {
        return Json.object();
    }

    @Override
    public void close() {
        docs.stop(0);
        attacker.close();
    }

    /** A TCP listener on loopback that records every connection and its first bytes. */
    static final class Attacker implements AutoCloseable {
        private final ServerSocket server;
        private final List<String> got = new CopyOnWriteArrayList<>();

        Attacker() throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread.ofVirtual().start(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket c = server.accept();
                        Thread.ofVirtual().start(() -> read(c));
                    } catch (IOException e) {
                        return;
                    }
                }
            });
        }

        private void read(Socket c) {
            try (c; InputStream in = c.getInputStream()) {
                c.setSoTimeout(2000);
                byte[] b = new byte[4096];
                int n = in.read(b);
                got.add(n > 0 ? new String(b, 0, n, StandardCharsets.ISO_8859_1) : "");
            } catch (IOException e) {
                got.add("");
            }
        }

        int port() {
            return server.getLocalPort();
        }

        String url() {
            return "http://127.0.0.1:" + port();
        }

        int connections() {
            return got.size();
        }

        String received() {
            return String.join("\n", got);
        }

        @Override
        public void close() {
            try {
                server.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
