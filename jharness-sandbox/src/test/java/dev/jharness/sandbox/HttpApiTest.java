package dev.jharness.sandbox;

import static org.junit.jupiter.api.Assertions.*;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import dev.jharness.config.HttpSettings;
import dev.jharness.config.Timeouts;
import dev.jharness.events.Json;
import dev.jharness.events.Origin;
import dev.jharness.net.EgressProxy;
import dev.jharness.net.NetworkRules;
import dev.jharness.net.RulesFile;
import dev.jharness.net.http.HttpClients;
import dev.jharness.net.http.HttpOperations;
import dev.jharness.security.FilePolicy;
import dev.jharness.security.PermissionEngine;
import dev.jharness.security.PermissionGate;
import dev.jharness.security.PermissionMode;
import dev.jharness.security.ProjectRules;
import dev.jharness.security.Prompter;

/** {@code api.http} and {@code api.fetch} from agent JS (spec 016, AC-04, AC-07). */
class HttpApiTest {

    @TempDir
    Path tmp;
    Fixture fx;
    HttpServer server;
    EgressProxy proxy;
    final AtomicInteger hits = new AtomicInteger();
    String base;

    @BeforeEach
    void setUp() throws Exception {
        fx = new Fixture(tmp);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", x -> {
            hits.incrementAndGet();
            if (x.getRequestURI().getPath().equals("/slow")) {
                try {
                    Thread.sleep(1800);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] b = ("<title>T</title><h1>Hi " + x.getRequestMethod() + "</h1>").getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", "text/html");
            x.sendResponseHeaders(200, b.length);
            try (OutputStream o = x.getResponseBody()) {
                o.write(b);
            }
        });
        server.start();
        base = "http://site.test:" + server.getAddress().getPort();
        Path g = tmp.resolve("home/.jharness/network.rules");
        Files.createDirectories(g.getParent());
        Files.writeString(g, "default deny\nallow site.test\n");
        proxy = EgressProxy.start(() -> new NetworkRules(RulesFile.load(g), RulesFile.load(tmp.resolve("p/network.rules")), true, List.of()),
                Prompter.none(), fx.bus, () -> Origin.main(1), tmp.resolve("p/network.rules"),
                h -> h.equals("site.test") ? new InetAddress[] {InetAddress.getLoopbackAddress()}
                        : new InetAddress[] {InetAddress.ofLiteral("93.184.216.34")});
        HttpClients.useProxy(proxy.address(), proxy.credentials());
    }

    @AfterEach
    void tearDown() {
        HttpClients.stopProxy(proxy.address());
        proxy.close();
        server.stop(0);
    }

    private ApiServices services(PermissionGate gate) {
        var http = new HttpOperations(fx.bus, () -> Origin.main(1), HttpSettings.defaults(), Timeouts.defaults());
        return new ApiServices(FilePolicy.workspaceOnly(fx.ws), gate, fx.bus, () -> Origin.main(1), fx.search, fx.git, null, null, http);
    }

    private RunResult run(Map<String, String> profile, PermissionGate gate, String js) throws Exception {
        try (AgentIsolate iso = Fixture.isolate(Fixture.profile(profile), fx.bus)) {
            return iso.run(js, services(gate));
        }
    }

    private PermissionEngine engine(String mode, Prompter.Answer answer) {
        return new PermissionEngine(PermissionMode.of(mode), ProjectRules.load(tmp.resolve("home"), fx.root), op -> answer, fx.bus,
                () -> Origin.main(1));
    }

    @Test
    void getAndFetchFromJs() throws Exception {
        RunResult r = run(Map.of(), engine("ask", Prompter.Answer.DENY), """
                const a = JSON.parse(api.http({url: '%s/a'}));
                const b = JSON.parse(api.http('%s/b'));
                const p = JSON.parse(api.fetch('%s/page'));
                ({a: a.status, body: a.body, b: b.status, title: p.title, text: p.text})
                """.formatted(base, base, base));
        assertEquals("ok", r.status(), r.toString());
        var v = Json.parse(r.value());
        assertEquals(200, v.get("a").asInt());
        assertTrue(v.get("body").asText().contains("<h1>Hi GET</h1>"));
        assertEquals(200, v.get("b").asInt());
        assertEquals("T", v.get("title").asText());
        assertEquals("# Hi GET", v.get("text").asText());
        assertTrue(fx.types().contains("http.request") && fx.types().contains("http.response"));
    }

    @Test
    void writesAskAndPlanModeDenies() throws Exception {
        String post = "api.http({method: 'POST', url: '" + base + "/x', body: '{}'})";
        RunResult denied = run(Map.of(), engine("ask", Prompter.Answer.DENY), post);
        assertEquals("error", denied.status());
        assertTrue(denied.error().contains("permission denied"), denied.error());
        RunResult plan = run(Map.of(), engine("plan", Prompter.Answer.ONCE), post);
        assertTrue(plan.error().contains("plan mode"), plan.error());
        assertEquals(0, hits.get(), "no request reached the server");
        assertTrue(fx.events.stream().anyMatch(e -> e.type().equals("permission.decided")
                && e.body().get("kind").asText().equals("http-write") && !e.body().get("allowed").asBoolean()));

        RunResult once = run(Map.of(), engine("ask", Prompter.Answer.ONCE), "JSON.parse(" + post + ").body");
        assertEquals("ok", once.status(), once.toString());
        assertTrue(once.value().contains("Hi POST"));
        assertEquals(1, hits.get());
    }

    @Test
    void badRequestsFailBeforeTheGate() throws Exception {
        RunResult r = run(Map.of(), PermissionGate.allowAll(), "api.http({url: 'file:///etc/passwd'})");
        assertTrue(r.error().contains("http:// or https://"), r.error());
        RunResult h = run(Map.of(), PermissionGate.allowAll(), "api.http({url: '" + base + "/', headers: {Host: 'evil'}})");
        assertTrue(h.error().contains("Host"), h.error());
        RunResult blocked = run(Map.of(), PermissionGate.allowAll(), "api.fetch('http://denied.test/')");
        assertTrue(blocked.error().contains("blocked by the network rules: denied.test"), blocked.error());
        assertTrue(fx.events.stream().noneMatch(e -> e.type().equals("permission.decided") && e.body().get("summary").asText().contains("file:")));
    }

    @Test
    void waitingDoesNotCountAgainstTheWallClock() throws Exception {
        RunResult r = run(Map.of("wallClock", "1s"), PermissionGate.allowAll(), "JSON.parse(api.http('" + base + "/slow')).status");
        assertEquals("ok", r.status(), r.toString());
        assertEquals("200", r.value());
    }
}
