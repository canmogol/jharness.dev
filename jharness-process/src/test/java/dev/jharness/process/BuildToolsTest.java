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
import dev.jharness.process.BuildOperations.Tool;
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
    Map<Tool, Executable> tools;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectories(tmp.resolve("ws")).toRealPath();
        EventBus bus = new EventBus("s-tools");
        net = new TestProxy(root, bus, tmp);
        assumeTrue(net.sandbox.available(), "no OS sandbox here: " + net.sandbox.reason());
        ProcessRunner runner = new ProcessRunner(bus, () -> Origin.main(1), 30_000, net.sandbox);
        tools = BuildOperations.findOnPath(root);
        ops = new BuildOperations(runner, new Workspace(root), Timeouts.defaults(), true, tools,
                Path.of(System.getProperty("user.home")), System.getenv("JAVA_HOME"));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (net != null) {
            net.close();
        }
    }

    private ProcessResult run(Tool t, String json) {
        assumeTrue(tools.containsKey(t), t.executable() + " is not installed");
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
        ProcessResult r = run(Tool.MAVEN, "{\"op\":\"compile\"}");
        assertEquals(0, r.exitCode(), r.output());
        assertTrue(r.output().contains("BUILD SUCCESS"), r.output());
        assertFalse(r.full().contains(net.password()), "the proxy password must not reach the output");
    }

    @Test
    void npmScriptsRunAndReachTheNetworkOnlyThroughTheProxy() throws Exception {
        Files.writeString(root.resolve("probe.js"), probeScript());
        Files.writeString(root.resolve("package.json"), "{\"name\":\"t\",\"version\":\"1.0.0\",\"scripts\":{"
                + "\"test\":\"node -e \\\"console.log('ok test')\\\"\","
                + "\"probe\":\"node probe.js 127.0.0.1:" + net.stubPort() + "\"}}");
        ProcessResult t = run(Tool.NPM, "{\"op\":\"test\"}");
        assertEquals(0, t.exitCode(), t.output());
        assertTrue(t.output().contains("ok test"), t.output());

        ProcessResult p = run(Tool.NPM, "{\"op\":\"run\",\"script\":\"probe\"}");
        assertTrue(p.output().contains("proxied ok"), p.output());
        assertTrue(p.output().contains("direct-failed"), p.output());
        assertFalse(p.full().contains(net.password()), p.full());
    }

    @Test
    void npmCantWriteOutsideItsCache() throws Exception {
        Path home = Path.of(System.getProperty("user.home"));
        String name = ".jharness-test-" + Long.toHexString(System.nanoTime());
        Files.writeString(root.resolve("package.json"), "{\"name\":\"t\",\"version\":\"1.0.0\",\"scripts\":{"
                + "\"test\":\"node -e \\\"require('fs').writeFileSync(require('os').homedir()+'/" + name + "','x')\\\"\"}}");
        ProcessResult r = run(Tool.NPM, "{\"op\":\"test\"}");
        assertNotEquals(0, r.exitCode(), r.output());
        assertFalse(Files.exists(home.resolve(name)));
    }

    @Test
    void gradleRunsInTheSandbox() throws Exception {
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 't'\n");
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'base' }\n");
        ProcessResult r = run(Tool.GRADLE, "{\"op\":\"build\",\"timeoutMs\":300000}");
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
