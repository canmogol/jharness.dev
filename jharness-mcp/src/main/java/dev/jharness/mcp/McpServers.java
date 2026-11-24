package dev.jharness.mcp;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.jharness.config.McpSettings;
import dev.jharness.events.EventBus;
import dev.jharness.events.Json;
import dev.jharness.events.Origin;
import dev.jharness.net.http.HttpClients;
import dev.jharness.process.ChildSandbox;
import dev.jharness.process.Executable;
import dev.jharness.process.Executables;
import dev.jharness.process.ProcessRunner;

/**
 * The session's MCP servers (spec 037, D-076), from the user's {@code mcp} settings only. A stdio server
 * starts through {@link ProcessRunner} in the OS sandbox, with the workspace as its folder, the folders it
 * names as writable and the network only through the egress proxy. An HTTP server is reached through the
 * proxy. Each connects at session start; one that fails is listed with its error.
 */
public final class McpServers implements AutoCloseable {

    static final Duration START = Duration.ofSeconds(30);

    /** One server: its client and tools, or why it isn't available. */
    public record Server(String name, String kind, McpClient client, ArrayNode tools, String error, Duration timeout,
            ProcessRunner.Live process) {}

    private final Map<String, Server> servers = new LinkedHashMap<>();
    private final EventBus bus;
    private final Supplier<Origin> origin;

    private McpServers(EventBus bus, Supplier<Origin> origin) {
        this.bus = bus;
        this.origin = origin;
    }

    /**
     * Connects to every configured server, in parallel.
     *
     * @param secrets resolves a secret name to its value, or null
     */
    public static McpServers start(McpSettings settings, ProcessRunner runner, Path workspace, Path home, Function<String, String> secrets,
            EventBus bus, Supplier<Origin> origin) {
        McpServers s = new McpServers(bus, origin);
        Map<String, Server> started = new ConcurrentHashMap<>();
        // Processes start on this thread: bubblewrap's --die-with-parent ends a child when the thread that
        // started it ends, so a short-lived thread would take the server with it. Only the handshakes run in parallel.
        Map<String, Object> opened = new LinkedHashMap<>();
        for (McpSettings.Server cfg : settings.servers().values()) {
            try {
                opened.put(cfg.name(), open(cfg, runner, workspace, home, secrets));
            } catch (Exception e) {
                opened.put(cfg.name(), e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }
        List<Thread> threads = new ArrayList<>();
        for (McpSettings.Server cfg : settings.servers().values()) {
            Object o = opened.get(cfg.name());
            String kind = cfg.stdio() ? "stdio" : "http";
            if (o instanceof String err) {
                started.put(cfg.name(), new Server(cfg.name(), kind, null, Json.array(), err, cfg.timeout(), null));
                continue;
            }
            Opened op = (Opened) o;
            threads.add(Thread.ofPlatform().daemon().name("mcp-start-" + cfg.name()).start(() -> started.put(cfg.name(), handshake(cfg, op))));
        }
        for (Thread t : threads) {
            try {
                t.join(START.plusSeconds(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        for (McpSettings.Server cfg : settings.servers().values()) {
            Server x = started.getOrDefault(cfg.name(),
                    new Server(cfg.name(), cfg.stdio() ? "stdio" : "http", null, Json.array(), "it didn't answer within " + START.toSeconds() + " s",
                            cfg.timeout(), null));
            s.servers.put(cfg.name(), x);
            var e = Json.object().put("server", x.name()).put("transport", x.kind()).put("tools", x.tools().size());
            if (x.error() != null) {
                e.put("error", x.error());
            }
            bus.publish(origin.get(), "mcp.connected", e);
        }
        return s;
    }

    /** A started server before its handshake. */
    private record Opened(McpClient client, ProcessRunner.Live live) {}

    private static Opened open(McpSettings.Server cfg, ProcessRunner runner, Path workspace, Path home, Function<String, String> secrets)
            throws Exception {
        ProcessRunner.Live live = null;
        McpClient client;
        {
            if (cfg.stdio()) {
                Executable exe = Executables.resolve(cfg.command(), workspace)
                        .orElseThrow(() -> new IllegalStateException(cfg.command() + " was not found on PATH outside the workspace"));
                ChildSandbox.ProxyRoute route = runner.sandbox().route();
                if (route == null) {
                    throw new IllegalStateException("stdio servers need the egress proxy, and this session has none");
                }
                Map<String, String> env = new LinkedHashMap<>();
                String proxy = "http://" + route.user() + ":" + route.password() + "@127.0.0.1:" + route.port();
                for (String k : List.of("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy")) {
                    env.put(k, proxy);
                }
                env.put("NO_PROXY", "localhost,127.0.0.1");
                env.put("no_proxy", "localhost,127.0.0.1");
                env.putAll(cfg.env());
                for (var e : cfg.secretEnv().entrySet()) {
                    String v = secrets.apply(e.getValue());
                    if (v == null || v.isBlank()) {
                        throw new IllegalStateException("secret " + e.getValue() + " for " + e.getKey() + " is not set (environment or ~/.jharness/secrets)");
                    }
                    env.put(e.getKey(), v.strip());
                }
                List<Path> writable = new ArrayList<>();
                for (String w : cfg.writable()) {
                    Path p = home.resolve(w.substring(2));
                    Files.createDirectories(p);
                    writable.add(p);
                }
                live = runner.start(exe, cfg.args(), workspace, env, new ChildSandbox.Grants(writable, true, false));
                client = McpClient.stdio(live.stdout(), live.stdin(), cfg.name());
            } else {
                String token = null;
                if (cfg.tokenSecret() != null) {
                    token = secrets.apply(cfg.tokenSecret());
                    if (token == null || token.isBlank()) {
                        throw new IllegalStateException("secret " + cfg.tokenSecret() + " is not set (environment or ~/.jharness/secrets)");
                    }
                    token = token.strip();
                }
                client = McpClient.http(HttpClients.create(), URI.create(cfg.url()), token);
            }
        }
        return new Opened(client, live);
    }

    private static Server handshake(McpSettings.Server cfg, Opened op) {
        String kind = cfg.stdio() ? "stdio" : "http";
        ProcessRunner.Live live = op.live();
        McpClient client = op.client();
        try {
            client.initialize(START);
            ArrayNode tools = client.listTools(START);
            return new Server(cfg.name(), kind, client, tools, null, cfg.timeout(), live);
        } catch (Exception e) {
            String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (live != null) {
                String err = live.stderr().strip();
                if (!err.isEmpty()) {
                    why += " (stderr: " + (err.length() > 500 ? err.substring(err.length() - 500) : err) + ")";
                }
                live.close();
            }
            if (client != null) {
                client.close();
            }
            return new Server(cfg.name(), kind, null, Json.array(), why, cfg.timeout(), null);
        }
    }

    public Map<String, Server> servers() {
        return Map.copyOf(servers);
    }

    public boolean any() {
        return !servers.isEmpty();
    }

    /** {@code api.mcp({list: true})} or one server's tools, with their input schemas. */
    public ArrayNode list(String server) {
        ArrayNode out = Json.array();
        for (Server s : servers.values()) {
            if (server != null && !server.equals(s.name())) {
                continue;
            }
            ObjectNode o = out.addObject().put("server", s.name()).put("transport", s.kind()).put("available", s.error() == null);
            if (s.error() != null) {
                o.put("error", s.error());
            }
            o.set("tools", s.tools());
        }
        if (server != null && out.isEmpty()) {
            throw new IllegalArgumentException(unknown(server));
        }
        return out;
    }

    private String unknown(String server) {
        return "no MCP server \"" + server + "\"" + (servers.isEmpty() ? " (none are set up in ~/.jharness/settings.json \"mcp\")"
                : " (servers: " + String.join(", ", servers.keySet()) + ")");
    }

    /** Checks a call before it is approved: the server is up and has the tool. */
    public Server check(String server, String tool) {
        Server s = servers.get(server);
        if (s == null) {
            throw new IllegalArgumentException(unknown(server));
        }
        if (s.error() != null) {
            throw new IllegalArgumentException("MCP server " + server + " isn't available: " + s.error());
        }
        boolean known = false;
        for (JsonNode t : s.tools()) {
            known |= t.path("name").asText().equals(tool);
        }
        if (!known) {
            List<String> names = new ArrayList<>();
            s.tools().forEach(t -> names.add(t.path("name").asText()));
            throw new IllegalArgumentException(server + " has no tool \"" + tool + "\" (tools: " + String.join(", ", names) + ")");
        }
        return s;
    }

    /** {@code tools/call}; the result as the server sent it. */
    public JsonNode call(String server, String tool, JsonNode input, Duration timeout) throws McpClient.McpException {
        Server s = check(server, tool);
        Duration t = timeout != null ? timeout : s.timeout();
        long t0 = System.nanoTime();
        try {
            JsonNode r = s.client().callTool(tool, input, t);
            bus.publish(origin.get(), "mcp.called", Json.object().put("server", server).put("tool", tool)
                    .put("isError", r.path("isError").asBoolean(false)).put("durationMs", (System.nanoTime() - t0) / 1_000_000));
            return r;
        } catch (McpClient.McpException e) {
            bus.publish(origin.get(), "mcp.called", Json.object().put("server", server).put("tool", tool).put("isError", true)
                    .put("error", e.getMessage()).put("durationMs", (System.nanoTime() - t0) / 1_000_000));
            throw e;
        }
    }

    @Override
    public void close() {
        for (Server s : servers.values()) {
            if (s.client() != null) {
                s.client().close();
            }
            if (s.process() != null) {
                s.process().close();
            }
        }
    }
}
