package dev.jharness.process;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.config.Timeouts;
import dev.jharness.events.EventBus;
import dev.jharness.events.Json;
import dev.jharness.events.Origin;
import dev.jharness.security.Workspace;

/**
 * Spec 013 AC-02, AC-08, AC-09: the real Maven, npm and Gradle in the OS sandbox, pointed at the
 * real egress proxy. Each test is skipped when its tool isn't installed.
 */
class BuildToolsTest {

    @TempDir
    Path tmp;
    Path root;
    TestProxy net;
    BuildOperations ops;
    Map<String, Executable> tools;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectories(tmp.resolve("ws")).toRealPath();
        EventBus bus = new EventBus("s-tools");
        net = new TestProxy(root, bus, tmp);
        assumeTrue(net.sandbox.available(), "no OS sandbox here: " + net.sandbox.reason());
        ProcessRunner runner = new ProcessRunner(bus, () -> Origin.main(1), 30_000, net.sandbox);
        tools = BuildOperations.findOnPath(ToolDefinitions.defaults(), root);
        ops = new BuildOperations(runner, new Workspace(root), Timeouts.defaults(), true, ToolDefinitions.defaults(), tools,
                Path.of(System.getProperty("user.home")), System.getenv("JAVA_HOME"), false);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (net != null) {
            net.close();
        }
    }

    private ProcessResult run(String t, String json) {
        assumeTrue(tools.containsKey(t), t + " is not installed");
        return ops.run(t, Json.parseObject(json));
    }

    @Test
    void mavenRunsInTheSandbox() throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>t</groupId><artifactId>t</artifactId><version>1</version><packaging>pom</packaging>
                </project>
                """);
        ProcessResult r = run("maven", "{\"op\":\"compile\"}");
        assertEquals(0, r.exitCode(), r.output());
        assertTrue(r.output().contains("BUILD SUCCESS"), r.output());
        assertFalse(r.full().contains(net.password()), "the proxy password must not reach the output");
    }

    /** Spec 023: install with skipTests, a profile and threads, in the sandbox; the artifact lands in ~/.m2/repository. */
    @Test
    void mavenInstallWithParameters() throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>dev.jharness.test</groupId><artifactId>build-params</artifactId><version>1</version><packaging>pom</packaging>
                  <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-install-plugin</artifactId>
                    <version>3.1.4</version></plugin></plugins></build>
                  <profiles><profile><id>dev</id></profile></profiles>
                </project>
                """);
        Path plugin = Path.of(System.getProperty("user.home"), ".m2/repository/org/apache/maven/plugins/maven-install-plugin/3.1.4");
        // The proxy here denies Maven Central, so the plugin must already be cached (it is once JHarness itself was built).
        assumeTrue(Files.isDirectory(plugin), "maven-install-plugin 3.1.4 is not in ~/.m2");
        Path installed = Path.of(System.getProperty("user.home"), ".m2", "repository", "dev", "jharness", "test", "build-params");
        try {
            ProcessResult r = run("maven", "{\"op\":\"install\",\"skipTests\":true,\"profiles\":\"dev\",\"threads\":\"2\",\"timeoutMs\":300000}");
            assertEquals(0, r.exitCode(), r.output());
            assertTrue(r.output().contains("BUILD SUCCESS"), r.output());
            assertTrue(Files.isRegularFile(installed.resolve("1/build-params-1.pom")), r.output());
        } finally {
            if (Files.exists(installed)) {
                try (var w = Files.walk(installed)) {
                    w.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                }
            }
            installed.getParent().toFile().delete();
        }
    }

    @Test
    void npmScriptsRunAndReachTheNetworkOnlyThroughTheProxy() throws Exception {
        Files.writeString(root.resolve("probe.js"), probeScript());
        Files.writeString(root.resolve("package.json"), "{\"name\":\"t\",\"version\":\"1.0.0\",\"scripts\":{"
                + "\"test\":\"node -e \\\"console.log('ok test')\\\"\","
                + "\"probe\":\"node probe.js 127.0.0.1:" + net.stubPort() + "\"}}");
        ProcessResult t = run("npm", "{\"op\":\"test\"}");
        assertEquals(0, t.exitCode(), t.output());
        assertTrue(t.output().contains("ok test"), t.output());

        ProcessResult p = run("npm", "{\"op\":\"run\",\"script\":\"probe\"}");
        assertTrue(p.output().contains("proxied ok"), p.output());
        assertTrue(p.output().contains("direct-failed"), p.output());
        assertFalse(p.full().contains(net.password()), p.full());
    }

    private void goModule(String goMod) throws Exception {
        Files.writeString(root.resolve("go.mod"), goMod);
        Files.writeString(root.resolve("calc.go"), "package calc\n\nfunc Add(a, b int) int { return a + b }\n");
        Files.writeString(root.resolve("calc_test.go"), """
                package calc

                import "testing"

                func TestAdd(t *testing.T) { if Add(2, 2) != 4 { t.Fatal("2+2") } }

                func TestBroken(t *testing.T) { t.Fatal("this one fails") }
                """);
    }

    /** Spec 059 AC-01, AC-05: build, vet, test (all, or one by name) and version in the sandbox; nothing starts git. */
    @Test
    void goBuildsTestsAndVetsInTheSandbox() throws Exception {
        goModule("module example.com/calc\n\ngo 1.22\n");
        ProcessResult v = run("go", "{\"op\":\"version\"}");
        assertEquals(0, v.exitCode(), v.output());
        assertTrue(v.output().startsWith("go version go"), v.output());
        ProcessResult b = run("go", "{\"op\":\"build\",\"timeoutMs\":300000}");
        assertEquals(0, b.exitCode(), b.output());
        ProcessResult vet = run("go", "{\"op\":\"vet\",\"timeoutMs\":300000}");
        assertEquals(0, vet.exitCode(), vet.output());
        ProcessResult all = run("go", "{\"op\":\"test\",\"timeoutMs\":300000}");
        assertNotEquals(0, all.exitCode(), all.output());
        assertTrue(all.output().contains("this one fails"), all.output());
        ProcessResult one = run("go", "{\"op\":\"test\",\"run\":\"^TestAdd$\",\"packages\":\"./...\",\"timeoutMs\":300000}");
        assertEquals(0, one.exitCode(), one.output());
        assertTrue(one.output().contains("ok"), one.output());
        assertFalse(one.full().contains(net.password()));
    }

    /** Spec 059 AC-04: GOTOOLCHAIN=local, so a go.mod asking for a newer Go fails instead of downloading one. */
    @Test
    void goNeverDownloadsAToolchain() throws Exception {
        goModule("module example.com/calc\n\ngo 1.99.0\n\ntoolchain go1.99.0\n");
        ProcessResult r = run("go", "{\"op\":\"build\",\"timeoutMs\":300000}");
        assertNotEquals(0, r.exitCode(), r.output());
        assertTrue(r.output().contains("GOTOOLCHAIN=local"), r.output());
    }

    /**
     * Spec 059 AC-02, AC-04: modules come only through the egress proxy, which here allows nothing but its stub, so
     * proxy.golang.org is refused; GOPROXY has no "direct", so Go never falls back to git.
     */
    @Test
    void goModulesComeOnlyThroughTheProxy() throws Exception {
        String home = System.getProperty("user.home");
        Path cached = Path.of(home, "go/pkg/mod/cache/download/rsc.io/quote/@v/v1.5.2.zip");
        assumeTrue(!Files.exists(cached), "rsc.io/quote v1.5.2 is already in the module cache");
        goModule("module example.com/calc\n\ngo 1.22\n\nrequire rsc.io/quote v1.5.2\n");
        ProcessResult r = run("go", "{\"op\":\"mod-download\",\"timeoutMs\":300000}");
        assertNotEquals(0, r.exitCode(), r.output());
        assertTrue(r.output().contains("proxy.golang.org"), r.output());
        assertFalse(r.output().contains("git"), "no version control: " + r.output());
        assertFalse(r.full().contains(net.password()));
    }

    /** Spec 059 AC-05: offline, a module builds from vendor/, and a download is refused by GOPROXY=off. */
    @Test
    void goBuildsOfflineFromVendor() throws Exception {
        Files.writeString(root.resolve("go.mod"), "module example.com/app\n\ngo 1.22\n\nrequire example.com/dep v1.0.0\n");
        Files.writeString(root.resolve("main.go"), "package main\n\nimport \"example.com/dep\"\n\nfunc main() { println(dep.Hi()) }\n");
        Files.createDirectories(root.resolve("vendor/example.com/dep"));
        Files.writeString(root.resolve("vendor/modules.txt"), "# example.com/dep v1.0.0\n## explicit\nexample.com/dep\n");
        Files.writeString(root.resolve("vendor/example.com/dep/dep.go"), "package dep\n\nfunc Hi() string { return \"hi\" }\n");
        BuildOperations off = new BuildOperations(new ProcessRunner(new EventBus("s-off"), () -> Origin.main(1), 30_000, net.sandbox),
                new Workspace(root), Timeouts.defaults(), true, ToolDefinitions.defaults(), tools, Path.of(System.getProperty("user.home")),
                System.getenv("JAVA_HOME"), true);
        assumeTrue(tools.containsKey("go"), "go is not installed");
        ProcessResult b = off.run("go", Json.parseObject("{\"op\":\"build\",\"timeoutMs\":300000}"));
        assertEquals(0, b.exitCode(), b.output());
        ProcessResult d = off.run("go", Json.parseObject("{\"op\":\"mod-download\",\"timeoutMs\":300000}"));
        assertTrue(d.exitCode() != 0 || d.output().isBlank(), d.output());
    }

    /** Spec 059 AC-01: gofmt checks and changes nothing. */
    @Test
    void gofmtListsUnformattedFilesAndChangesNothing() throws Exception {
        Files.createDirectories(root.resolve("pkg"));
        String messy = "package pkg\nfunc   X( ) int {return 1}\n";
        Files.writeString(root.resolve("pkg/x.go"), messy);
        Files.writeString(root.resolve("ok.go"), "package ok\n\nfunc Y() int { return 1 }\n");
        ProcessResult r = run("gofmt", "{\"op\":\"check\"}");
        assertEquals(0, r.exitCode(), r.output());
        assertTrue(r.output().contains("pkg/x.go") && !r.output().contains("ok.go"), r.output());
        ProcessResult f = run("gofmt", "{\"op\":\"check-folder\",\"folder\":\"pkg\"}");
        assertTrue(f.output().contains("x.go"), f.output());
        assertEquals(messy, Files.readString(root.resolve("pkg/x.go")));
    }

    @Test
    void npmCantWriteOutsideItsCache() throws Exception {
        Path home = Path.of(System.getProperty("user.home"));
        String name = ".jharness-test-" + Long.toHexString(System.nanoTime());
        Files.writeString(root.resolve("package.json"), "{\"name\":\"t\",\"version\":\"1.0.0\",\"scripts\":{"
                + "\"test\":\"node -e \\\"require('fs').writeFileSync(require('os').homedir()+'/" + name + "','x')\\\"\"}}");
        ProcessResult r = run("npm", "{\"op\":\"test\"}");
        assertNotEquals(0, r.exitCode(), r.output());
        assertFalse(Files.exists(home.resolve(name)));
    }

    @Test
    void gradleRunsInTheSandbox() throws Exception {
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 't'\n");
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'base' }\n");
        ProcessResult r = run("gradle", "{\"op\":\"build\",\"timeoutMs\":300000}");
        assertEquals(0, r.exitCode(), r.output());
        assertTrue(r.output().contains("BUILD SUCCESSFUL"), r.output());
    }

    /**
     * Connects to the proxy from {@code npm_config_https_proxy}, tunnels to the stub with CONNECT and
     * reads its body ("proxied ok"), then tries the stub directly ("direct-failed ...").
     */
    static String probeScript() {
        return """
                const net = require('net');
                const u = new URL(process.env.npm_config_https_proxy);
                const target = process.argv[2];
                const port = Number(target.split(':')[1]);
                const auth = Buffer.from(decodeURIComponent(u.username) + ':' + decodeURIComponent(u.password)).toString('base64');
                function direct(done) {
                  const s = net.connect({ host: '127.0.0.1', port });
                  s.setTimeout(3000);
                  s.on('connect', () => { done('direct-connected'); s.destroy(); });
                  s.on('error', (e) => done('direct-failed ' + e.code));
                  s.on('timeout', () => { done('direct-failed timeout'); s.destroy(); });
                }
                function proxied(done) {
                  const s = net.connect({ host: u.hostname, port: Number(u.port) });
                  let buf = '';
                  let tunnelled = false;
                  s.setTimeout(8000);
                  s.on('connect', () => s.write('CONNECT ' + target + ' HTTP/1.1\\r\\nHost: ' + target
                      + '\\r\\nProxy-Authorization: Basic ' + auth + '\\r\\n\\r\\n'));
                  s.on('data', (d) => {
                    buf += d;
                    if (!tunnelled && buf.includes('\\r\\n\\r\\n')) {
                      tunnelled = true;
                      if (!/^HTTP\\/1\\.[01] 200/.test(buf)) { done('proxy-refused ' + buf.split('\\r\\n')[0]); s.destroy(); return; }
                      buf = '';
                      s.write('GET / HTTP/1.1\\r\\nHost: ' + target + '\\r\\nConnection: close\\r\\n\\r\\n');
                    }
                  });
                  s.on('end', () => done(buf.includes('hello from stub') ? 'proxied ok' : 'proxied bad ' + JSON.stringify(buf)));
                  s.on('error', (e) => done('proxied-failed ' + e.message));
                  s.on('timeout', () => { done('proxied-failed timeout'); s.destroy(); });
                }
                let once = false;
                proxied((a) => { if (once) return; once = true; console.log(a); if (a !== 'proxied ok') process.exitCode = 1; direct((b) => console.log(b)); });
                """;
    }
}
