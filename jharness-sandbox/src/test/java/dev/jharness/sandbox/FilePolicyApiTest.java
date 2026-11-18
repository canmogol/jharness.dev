package dev.jharness.sandbox;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.config.Timeouts;
import dev.jharness.events.Json;
import dev.jharness.events.Origin;
import dev.jharness.process.Search;
import dev.jharness.security.FilePolicy;
import dev.jharness.security.PermissionGate;

/** The file policy through the api, with an allow-all gate, as in bypass mode (AC-05, AC-06). */
class FilePolicyApiTest {

    @TempDir
    Path tmp;
    Fixture fx;
    AgentIsolate iso;
    Path docs;
    ApiServices api;

    @BeforeEach
    void setUp() throws Exception {
        fx = new Fixture(tmp);
        docs = Files.createDirectories(tmp.resolve("docs")).toRealPath();
        Files.writeString(docs.resolve("guide.md"), "Use TODO markers.\n");
        Files.writeString(fx.root.resolve(".env"), "TODO=secret\n");
        Files.createDirectories(fx.root.resolve("keys"));
        Files.writeString(fx.root.resolve("keys/server.pem"), "TODO private\n");
        Files.createDirectories(fx.root.resolve("secrets"));
        Files.writeString(fx.root.resolve("secrets/db.txt"), "TODO password\n");
        FilePolicy policy = new FilePolicy(fx.ws, List.of(docs), List.of("secrets/**"));
        Search search = Search.create(fx.ws, fx.bus, () -> Origin.main(1), Timeouts.defaults(), policy.denyList());
        api = new ApiServices(policy, PermissionGate.allowAll(), fx.bus, () -> Origin.main(1), search, fx.git);
        iso = Fixture.isolate(Fixture.profile(Map.of()), fx.bus);
    }

    @AfterEach
    void tearDown() {
        iso.close();
    }

    private RunResult run(String js) {
        return iso.run(js, api);
    }

    @Test
    void mountsAreReadableButNotWritable() throws Exception {
        String guide = docs.resolve("guide.md").toString();
        assertEquals("Use TODO markers.\n", run("api.readFile('" + guide + "')").value());
        var files = Json.parse(run("api.listFiles({path: '" + docs + "'})").value());
        assertEquals("[\"" + guide + "\"]", files.get("files").toString());
        var hits = Json.parse(run("api.grep({pattern: 'TODO', path: '" + docs + "'})").value());
        assertEquals(guide, hits.get("matches").get(0).get("path").asText());
        RunResult w = run("api.writeFile('" + guide + "', 'x')");
        assertTrue(w.error().contains("read-only"), w.error());
        assertTrue(run("api.editFile('" + guide + "', 'Use', 'Avoid')").error().contains("read-only"));
        assertEquals("Use TODO markers.\n", Files.readString(docs.resolve("guide.md")));
        var ctx = Json.parse(run("api.readContext()").value());
        assertEquals("[\"" + docs + "\"]", ctx.get("mounts").toString());
    }

    @Test
    void denyListBlocksReadsWritesAndResults() throws Exception {
        for (String js : List.of("api.readFile('.env')", "api.readFile('keys/server.pem')", "api.readFile('secrets/db.txt')",
                "api.writeFile('.env', 'x')", "api.editFile('.env', 'TODO', 'X')", "api.readFile('.git/config')",
                "api.writeFile('.git/hooks/pre-commit', 'x')", "api.listFiles({path: 'secrets'})", "api.git({op: 'add', paths: ['.env']})")) {
            RunResult r = run(js);
            assertEquals("error", r.status(), js);
            assertTrue(r.error().contains("deny list"), js + " -> " + r.error());
        }
        var hits = Json.parse(run("api.grep({pattern: 'TODO'})").value()).get("matches").toString();
        assertFalse(hits.contains(".env") || hits.contains("server.pem") || hits.contains("secrets"), hits);
        assertTrue(hits.contains("src/App.java"));
        var files = Json.parse(run("api.listFiles({})").value()).get("files").toString();
        assertFalse(files.contains("pem") || files.contains("secrets") || files.contains(".env"), files);
        var ctx = Json.parse(run("api.readContext()").value()).get("files").toString();
        assertFalse(ctx.contains("secrets"), ctx);
    }

    @Test
    void projectConfigurationIsNotWritable() {
        RunResult r = run("api.writeFile('.jharness/settings.json', '{\"permissions\":{\"mode\":\"bypass\"}}')");
        assertTrue(r.error().contains("JHarness project configuration"), r.error());
        assertFalse(Files.exists(fx.root.resolve(".jharness/settings.json")));
    }
}
