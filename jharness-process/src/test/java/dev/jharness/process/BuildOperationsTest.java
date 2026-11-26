package dev.jharness.process;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.config.Timeouts;
import dev.jharness.events.EventBus;
import dev.jharness.events.Json;
import dev.jharness.events.Origin;
import dev.jharness.security.Workspace;

/** Spec 013 AC-01, AC-02, AC-04, AC-06, AC-08: request validation, argv, wrappers, proxy settings. */
class BuildOperationsTest {

    @TempDir
    Path tmp;
    Path root;
    Workspace ws;
    Path home;
    ProcessRunner runner;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectories(tmp.resolve("ws")).toRealPath();
        home = Files.createDirectories(tmp.resolve("home")).toRealPath();
        ws = new Workspace(root);
        runner = new ProcessRunner(new EventBus("s-b"), () -> Origin.main(1), 30_000, TestSandboxes.in(root));
    }

    private BuildOperations ops(boolean trusted, Map<String, Executable> onPath) {
        return new BuildOperations(runner, ws, Timeouts.defaults(), trusted, ToolDefinitions.defaults(), onPath, home, null, false);
    }

    private BuildOperations ops() {
        return ops(true, Map.of("maven", new Executable("mvn", Path.of("/opt/maven/bin/mvn")),
                "gradle", new Executable("gradle", Path.of("/opt/gradle/bin/gradle")),
                "npm", new Executable("npm", Path.of("/opt/node/lib/npm-cli.js"), Path.of("/opt/node/bin"))));
    }

    static ObjectNode req(String json) {
        return Json.parseObject(json);
    }

    private String fails(String t, String json) {
        return assertThrows(RuntimeException.class, () -> ops().check(t, req(json))).getMessage();
    }

    @Test
    void mavenArgv() throws Exception {
        Files.createDirectories(root.resolve("core"));
        assertEquals(List.of("-B", "-ntp", "-Dstyle.color=never", "test"), ops().args("maven", req("{\"op\":\"test\"}")));
        assertEquals(List.of("-B", "-ntp", "-Dstyle.color=never", "--global-settings", "/s.xml", "-pl", "core", "-am",
                "-Dtest=AppTest#adds", "-Dsurefire.failIfNoSpecifiedTests=false", "test"),
                ops().args("maven", req("{\"op\":\"test\",\"module\":\"core\",\"tests\":\"AppTest#adds\"}"), Path.of("/s.xml")));
    }

    @Test
    void gradleArgv() {
        assertEquals(List.of("--console=plain", "--no-daemon", "build"), ops().args("gradle", req("{\"op\":\"build\"}")));
        assertEquals(List.of("--console=plain", "--no-daemon", ":app:test", "--tests", "com.x.*Test"),
                ops().args("gradle", req("{\"op\":\"test\",\"project\":\":app\",\"tests\":\"com.x.*Test\"}")));
    }

    @Test
    void npmArgv() throws Exception {
        Files.writeString(root.resolve("package.json"), "{\"scripts\":{\"lint\":\"eslint .\",\"test\":\"node t.js\"}}");
        assertEquals(List.of("ci", "--ignore-scripts"), ops().args("npm", req("{\"op\":\"ci\"}")));
        assertEquals(List.of("install", "--ignore-scripts"), ops().args("npm", req("{\"op\":\"install\"}")));
        assertEquals(List.of("test"), ops().args("npm", req("{\"op\":\"test\"}")));
        assertEquals(List.of("run", "lint"), ops().args("npm", req("{\"op\":\"run\",\"script\":\"lint\"}")));
        assertTrue(fails("npm", "{\"op\":\"run\",\"script\":\"build\"}").contains("no script \"build\""));
        assertTrue(fails("npm", "{\"op\":\"run\"}").contains("needs a \"script\""));
    }

    @Test
    void rejectsUnknownOpsFieldsAndOptions() throws Exception {
        assertTrue(fails("maven", "{\"op\":\"deploy\"}").contains("must be one of clean, compile, install, package, test, verify"));
        assertTrue(fails("maven", "{\"op\":\"test\",\"args\":[\"-X\"]}").contains("does not take \"args\""));
        assertTrue(fails("maven", "{\"op\":\"compile\",\"tests\":\"A\"}").contains("does not take \"tests\""));
        assertTrue(fails("maven", "{\"op\":\"test\",\"tests\":\"-Dexec=x\"}").matches("(?s).*(characters|can't start with '-').*"));
        assertTrue(fails("maven", "{\"op\":\"test\",\"tests\":\"A B\"}").matches("(?s).*(characters|can't start with '-').*"));
        assertTrue(fails("maven", "{\"op\":\"test\",\"module\":\"../x\"}").contains("outside"), fails("maven", "{\"op\":\"test\",\"module\":\"../x\"}"));
        assertTrue(fails("gradle", "{\"op\":\"test\",\"project\":\"app --init-script x\"}").matches("(?s).*(characters|can't start with '-').*"));
        assertTrue(fails("gradle", "{\"op\":\"build\",\"tests\":\"A\"}").contains("does not take"));
        Files.writeString(root.resolve("package.json"), "{\"scripts\":{\"-x\":\"y\"}}");
        assertTrue(fails("npm", "{\"op\":\"run\",\"script\":\"-x\"}").matches("(?s).*(characters|can't start with '-').*"));
    }

    @Test
    void timeoutMsIsCheckedAgainstTheMaximum() {
        assertEquals(Duration.ofMinutes(2), CallTimeout.of(req("{}"), Duration.ofMinutes(2), Duration.ofMinutes(10)));
        assertEquals(Duration.ofMinutes(5), CallTimeout.of(req("{\"timeoutMs\":300000}"), Duration.ofMinutes(2), Duration.ofMinutes(10)));
        assertTrue(fails("maven", "{\"op\":\"test\",\"timeoutMs\":600001}").contains("from 1 to 600000"));
        assertTrue(fails("maven", "{\"op\":\"test\",\"timeoutMs\":0}").contains("from 1 to 600000"));
        assertTrue(fails("maven", "{\"op\":\"test\",\"timeoutMs\":\"5m\"}").contains("whole number"));
        ops().check("maven", req("{\"op\":\"test\",\"timeoutMs\":600000}"));
    }

    @Test
    void untrustedProjectsCantBuild() {
        var e = assertThrows(OperationException.class, () -> ops(false, Map.of()).check("npm", req("{\"op\":\"test\"}")));
        assertTrue(e.getMessage().contains("trust this project first"), e.getMessage());
    }

    @Test
    void missingToolsSaySo() {
        var e = assertThrows(OperationException.class, () -> ops(true, Map.of()).check("maven", req("{\"op\":\"test\"}")));
        assertTrue(e.getMessage().contains("maven is not installed"), e.getMessage());
    }

    @Test
    void wrappersRunInTrustedProjectsAndGradlewMustPinItsChecksum() throws Exception {
        Path mvnw = Files.writeString(root.resolve("mvnw"), "#!/bin/sh\n");
        Files.setPosixFilePermissions(mvnw, PosixFilePermissions.fromString("rwx------"));
        assertEquals(root.resolve("mvnw"), ops().executable("maven").path());

        Path gradlew = Files.writeString(root.resolve("gradlew"), "#!/bin/sh\n");
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwx------"));
        Path props = Files.createDirectories(root.resolve("gradle/wrapper")).resolve("gradle-wrapper.properties");
        Files.writeString(props, "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.0-bin.zip\n");
        var e = assertThrows(OperationException.class, () -> ops().executable("gradle"));
        assertTrue(e.getMessage().contains("distributionSha256Sum"), e.getMessage());
        Files.writeString(props, "distributionSha256Sum=" + "ab".repeat(32) + "\n", java.nio.file.StandardOpenOption.APPEND);
        assertEquals(root.resolve("gradlew"), ops().executable("gradle").path());

        assertEquals(Path.of("/opt/node/lib/npm-cli.js"), ops().executable("npm").path());
    }

    @Test
    void cachesAreTheToolsOwnFoldersOnly() {
        assertEquals(List.of(home.resolve(".m2/repository"), home.resolve(".m2/wrapper")), ops().caches("maven"));
        assertEquals(List.of(home.resolve(".npm")), ops().caches("npm"));
        List<Path> g = ops().caches("gradle");
        assertTrue(g.contains(home.resolve(".gradle/caches")) && g.contains(home.resolve(".gradle/wrapper")), g.toString());
        assertFalse(g.contains(home.resolve(".gradle")) || g.contains(home.resolve(".gradle/init.d")), g.toString());
    }

    @Test
    void toolsArePointedAtTheProxyWithoutArgv() {
        var route = new ChildSandbox.ProxyRoute(4321, tmp.resolve("net/proxy.sock"), "jharness", "s3cr3t");
        Map<String, String> npm = ops().env("npm", ops().executable("npm"), route, tmp);
        assertEquals("http://jharness:s3cr3t@127.0.0.1:4321", npm.get("npm_config_https_proxy"));
        assertEquals("http://jharness:s3cr3t@127.0.0.1:4321", npm.get("npm_config_proxy"));
        assertEquals("false", npm.get("npm_config_update_notifier"));
        assertTrue(npm.get("PATH").startsWith("/opt/node/bin:"), npm.get("PATH"));

        String jto = ops().env("gradle", ops().executable("gradle"), route, tmp).get("JAVA_TOOL_OPTIONS");
        assertTrue(jto.contains("-Dhttps.proxyHost=127.0.0.1") && jto.contains("-Dhttps.proxyPort=4321")
                && jto.contains("-Dhttps.proxyPassword=s3cr3t") && jto.contains("-Djdk.http.auth.tunneling.disabledSchemes=")
                && jto.contains("-Djava.io.tmpdir=" + tmp) && jto.contains("-Djava.net.preferIPv4Stack=true"), jto);

        String xml = BuildOperations.mavenSettings(route);
        assertTrue(xml.contains("<protocol>https</protocol><host>127.0.0.1</host><port>4321</port><username>jharness</username><password>s3cr3t</password>"), xml);
        assertFalse(ops().args("maven", req("{\"op\":\"test\"}"), Path.of("/s.xml")).toString().contains("s3cr3t"));
    }

    private BuildOperations goOps(boolean offline) {
        return new BuildOperations(runner, ws, Timeouts.defaults(), true, ToolDefinitions.defaults(),
                Map.of("go", new Executable("go", Path.of("/opt/go/bin/go")), "gofmt", new Executable("gofmt", Path.of("/opt/go/bin/gofmt"))),
                home, null, offline);
    }

    /** Spec 059 AC-01: Go's operations as argv; values that start with "-" (or leave the module) are refused. */
    @Test
    void goOperationsAndTheirArguments() throws Exception {
        BuildOperations g = goOps(false);
        assertEquals(List.of("build", "./..."), g.args("go", req("{\"op\":\"build\"}")));
        assertEquals(List.of("test", "./..."), g.args("go", req("{\"op\":\"test\"}")));
        assertEquals(List.of("test", "-run", "^TestParse$", "./internal/store"),
                g.args("go", req("{\"op\":\"test\",\"run\":\"^TestParse$\",\"packages\":\"./internal/store\"}")));
        assertEquals(List.of("test", "-run", "TestStore/empty", "example.com/m/api/..."),
                g.args("go", req("{\"op\":\"test\",\"run\":\"TestStore/empty\",\"packages\":\"example.com/m/api/...\"}")));
        assertEquals(List.of("vet", "."), g.args("go", req("{\"op\":\"vet\",\"packages\":\".\"}")));
        assertEquals(List.of("mod", "tidy"), g.args("go", req("{\"op\":\"mod-tidy\"}")));
        assertEquals(List.of("mod", "download"), g.args("go", req("{\"op\":\"mod-download\"}")));
        assertEquals(List.of("version"), g.args("go", req("{\"op\":\"version\"}")));
        for (String bad : List.of("-exec=sh", "-toolexec=/tmp/x", "-ldflags=-X", "--", "../other/...", "./../x", "/abs/path", "a b", "./...;x")) {
            assertThrows(OperationException.class, () -> g.args("go", req("{\"op\":\"build\",\"packages\":" + Json.MAPPER.writeValueAsString(bad) + "}")),
                    "packages " + bad);
        }
        for (String bad : List.of("-v", "-exec=sh", "-toolexec", "-", "a b")) {
            assertThrows(OperationException.class, () -> g.args("go", req("{\"op\":\"test\",\"run\":" + Json.MAPPER.writeValueAsString(bad) + "}")),
                    "run " + bad);
        }
        assertThrows(OperationException.class, () -> g.args("go", req("{\"op\":\"mod-tidy\",\"packages\":\"./...\"}")), "mod tidy takes no packages");
        assertThrows(OperationException.class, () -> g.args("go", req("{\"op\":\"generate\"}")), "no go generate");
        assertEquals(List.of("-l", "."), g.args("gofmt", req("{\"op\":\"check\"}")));
        assertThrows(OperationException.class, () -> g.args("gofmt", req("{\"op\":\"check-folder\",\"folder\":\"-w\"}")));
    }

    /**
     * Spec 059 AC-02, AC-04, AC-05: Go gets the proxy, a module proxy without "direct", the checksum database, no VCS,
     * no toolchain downloads, no go env file, and caches in the home; offline, no module proxy at all.
     */
    @Test
    void goEnvironment() {
        var route = new ChildSandbox.ProxyRoute(4321, tmp.resolve("net/proxy.sock"), "jharness", "s3cr3t");
        BuildOperations g = goOps(false);
        Map<String, String> env = g.env("go", g.executable("go"), route, tmp);
        assertEquals("http://jharness:s3cr3t@127.0.0.1:4321", env.get("HTTPS_PROXY"));
        assertEquals("http://jharness:s3cr3t@127.0.0.1:4321", env.get("https_proxy"));
        assertEquals("https://proxy.golang.org", env.get("GOPROXY"));
        assertFalse(env.get("GOPROXY").contains("direct"));
        assertEquals("sum.golang.org", env.get("GOSUMDB"));
        assertEquals("local", env.get("GOTOOLCHAIN"));
        assertEquals("-buildvcs=false", env.get("GOFLAGS"));
        assertEquals("*:off", env.get("GOVCS"));
        assertEquals("off", env.get("GOENV"));
        assertEquals(home.resolve("go").toString(), env.get("GOPATH"));
        assertEquals(home.resolve("go/pkg/mod").toString(), env.get("GOMODCACHE"));
        assertEquals(home.resolve(".cache/go-build").toString(), env.get("GOCACHE"));
        assertTrue(env.get("PATH").startsWith("/opt/go/bin:"), env.get("PATH"));
        assertDoesNotThrow(() -> ProcessRunner.checkEnv(env));
        assertEquals(List.of(home.resolve("go/pkg"), home.resolve(".cache/go-build")), g.caches("go"));
        BuildOperations off = goOps(true);
        assertEquals("off", off.env("go", off.executable("go"), route, tmp).get("GOPROXY"));
        assertNull(g.env("gofmt", g.executable("gofmt"), route, tmp).get("HTTPS_PROXY"), "gofmt has no network");
    }

    @Test
    void offlineModeRunsToolsOffline() {
        var exes = Map.of("maven", new Executable("mvn", Path.of("/opt/maven/bin/mvn")),
                "gradle", new Executable("gradle", Path.of("/opt/gradle/bin/gradle")),
                "npm", new Executable("npm", Path.of("/opt/node/lib/npm-cli.js"), Path.of("/opt/node/bin")));
        BuildOperations off = new BuildOperations(runner, ws, Timeouts.defaults(), true, ToolDefinitions.defaults(), exes, home, null, true);
        assertTrue(off.args("maven", req("{\"op\":\"test\"}")).contains("-o"));
        assertTrue(off.args("gradle", req("{\"op\":\"test\"}")).contains("--offline"));
        var route = new ChildSandbox.ProxyRoute(4321, tmp.resolve("net/proxy.sock"), "jharness", "s3cr3t");
        assertEquals("true", off.env("npm", off.executable("npm"), route, tmp).get("npm_config_offline"));
        assertDoesNotThrow(() -> ProcessRunner.checkEnv(off.env("npm", off.executable("npm"), route, tmp)));
        assertFalse(ops().args("maven", req("{\"op\":\"test\"}")).contains("-o"));
        assertFalse(ops().args("gradle", req("{\"op\":\"test\"}")).contains("--offline"));
        assertNull(ops().env("npm", ops().executable("npm"), route, tmp).get("npm_config_offline"));
    }

    /** Spec 023: typed build parameters. */
    @Test
    void mavenParameters() {
        List<String> base = List.of("-B", "-ntp", "-Dstyle.color=never");
        assertEquals(cat(base, "-DskipTests", "-P", "dev,!ci", "-T", "1C", "install"),
                ops().args("maven", req("{\"op\":\"install\",\"skipTests\":true,\"profiles\":\"dev,!ci\",\"threads\":\"1C\"}")));
        assertEquals(cat(base, "-DskipTests", "package"), ops().args("maven", req("{\"op\":\"package\",\"skipTests\":true}")));
        assertEquals(cat(base, "package"), ops().args("maven", req("{\"op\":\"package\",\"skipTests\":false}")));
        assertEquals(cat(base, "-T", "4", "verify"), ops().args("maven", req("{\"op\":\"verify\",\"threads\":\"4\"}")));
        assertTrue(fails("maven", "{\"op\":\"test\",\"skipTests\":true}").contains("does not take \"skipTests\""));
        assertTrue(fails("maven", "{\"op\":\"verify\",\"tests\":\"A\",\"skipTests\":true}").contains("can't be used together"));
        assertTrue(fails("maven", "{\"op\":\"package\",\"skipTests\":\"true\"}").contains("must be true or false"));
        for (String bad : List.of("-Dexec.args=x", "dev ci", "dev,", "a/b", "dev;rm")) {
            assertTrue(fails("maven", "{\"op\":\"package\",\"profiles\":\"" + bad + "\"}").matches("(?s).*(characters|can't start with '-').*"), bad);
        }
        for (String bad : List.of("0", "33", "5C", "1C -X", "-1")) {
            assertTrue(fails("maven", "{\"op\":\"package\",\"threads\":\"" + bad + "\"}").matches("(?s).*(characters|can't start with '-').*"), bad);
        }
        assertTrue(fails("maven", "{\"op\":\"clean\",\"profiles\":\"dev\"}").contains("does not take"));
    }

    @Test
    void gradleAndNpmParameters() throws Exception {
        List<String> base = List.of("--console=plain", "--no-daemon");
        assertEquals(cat(base, ":app:build", "-x", "test", "--parallel", "--continue"),
                ops().args("gradle", req("{\"op\":\"build\",\"project\":\":app\",\"skipTests\":true,\"parallel\":true,\"continue\":true}")));
        assertEquals(cat(base, "check", "-x", "test"), ops().args("gradle", req("{\"op\":\"check\",\"skipTests\":true}")));
        assertTrue(fails("gradle", "{\"op\":\"test\",\"skipTests\":true}").contains("does not take"));
        assertTrue(fails("gradle", "{\"op\":\"assemble\",\"skipTests\":true}").contains("does not take"));
        assertTrue(fails("gradle", "{\"op\":\"build\",\"parallel\":1}").contains("must be true or false"));
        assertEquals(List.of("ci", "--ignore-scripts", "--omit=dev"), ops().args("npm", req("{\"op\":\"ci\",\"omitDev\":true}")));
        assertEquals(List.of("install", "--ignore-scripts", "--omit=dev"), ops().args("npm", req("{\"op\":\"install\",\"omitDev\":true}")));
        assertTrue(fails("npm", "{\"op\":\"test\",\"omitDev\":true}").contains("does not take"));
    }

    @Test
    void approvalSummaryShowsTheParameters() {
        assertEquals("maven package · skipTests, profiles dev",
                ops().summary("maven", req("{\"op\":\"package\",\"skipTests\":true,\"profiles\":\"dev\"}")));
        assertEquals("gradle build · project :app, parallel",
                ops().summary("gradle", req("{\"op\":\"build\",\"project\":\":app\",\"parallel\":true,\"continue\":false}")));
        assertEquals("npm test", ops().summary("npm", req("{\"op\":\"test\"}")));
        assertEquals("{\"tool\":\"maven\",\"source\":\"user\",\"executable\":\"/opt/maven/bin/mvn\",\"op\":\"maven package (/opt/maven/bin/mvn)\"}",
                Json.write(ops().detail("maven", req("{\"op\":\"package\",\"skipTests\":true}"))), "\"always\" saves the operation alone");
    }

    private static List<String> cat(List<String> a, String... b) {
        List<String> out = new java.util.ArrayList<>(a);
        out.addAll(List.of(b));
        return out;
    }

    @Test
    void permissionDetailNamesToolAndOp() {
        assertEquals("maven test (/opt/maven/bin/mvn)", ops().detail("maven", req("{\"op\":\"test\"}")).get("op").asText());
    }

    @Test
    void noProxyNoBuild() throws Exception {
        assumeTrue(runner.sandbox().available());
        Files.writeString(root.resolve("package.json"), "{\"scripts\":{\"test\":\"node -e 1\"}}");
        var e = assertThrows(OperationException.class, () -> ops().run("npm", req("{\"op\":\"test\"}")));
        assertTrue(e.getMessage().contains("egress proxy"), e.getMessage());
    }
}
