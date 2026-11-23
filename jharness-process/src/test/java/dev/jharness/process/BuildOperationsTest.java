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
import dev.jharness.process.BuildOperations.Tool;
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

    private BuildOperations ops(boolean trusted, Map<Tool, Executable> onPath) {
        return new BuildOperations(runner, ws, Timeouts.defaults(), trusted, onPath, home, null);
    }

    private BuildOperations ops() {
        return ops(true, Map.of(Tool.MAVEN, new Executable("mvn", Path.of("/opt/maven/bin/mvn")),
                Tool.GRADLE, new Executable("gradle", Path.of("/opt/gradle/bin/gradle")),
                Tool.NPM, new Executable("npm", Path.of("/opt/node/lib/npm-cli.js"), Path.of("/opt/node/bin"))));
    }

    static ObjectNode req(String json) {
        return Json.parseObject(json);
    }

    private String fails(Tool t, String json) {
        return assertThrows(RuntimeException.class, () -> ops().check(t, req(json))).getMessage();
    }

    @Test
    void mavenArgv() throws Exception {
        Files.createDirectories(root.resolve("core"));
        assertEquals(List.of("-B", "-ntp", "-Dstyle.color=never", "test"), ops().args(Tool.MAVEN, req("{\"op\":\"test\"}")));
        assertEquals(List.of("-B", "-ntp", "-Dstyle.color=never", "--global-settings", "/s.xml", "-pl", "core", "-am",
                "-Dtest=AppTest#adds", "-Dsurefire.failIfNoSpecifiedTests=false", "test"),
                ops().args(Tool.MAVEN, req("{\"op\":\"test\",\"module\":\"core\",\"tests\":\"AppTest#adds\"}"), Path.of("/s.xml")));
    }

    @Test
    void gradleArgv() {
        assertEquals(List.of("--console=plain", "--no-daemon", "build"), ops().args(Tool.GRADLE, req("{\"op\":\"build\"}")));
        assertEquals(List.of("--console=plain", "--no-daemon", ":app:test", "--tests", "com.x.*Test"),
                ops().args(Tool.GRADLE, req("{\"op\":\"test\",\"project\":\":app\",\"tests\":\"com.x.*Test\"}")));
    }

    @Test
    void npmArgv() throws Exception {
        Files.writeString(root.resolve("package.json"), "{\"scripts\":{\"lint\":\"eslint .\",\"test\":\"node t.js\"}}");
        assertEquals(List.of("ci", "--ignore-scripts"), ops().args(Tool.NPM, req("{\"op\":\"ci\"}")));
        assertEquals(List.of("install", "--ignore-scripts"), ops().args(Tool.NPM, req("{\"op\":\"install\"}")));
        assertEquals(List.of("test"), ops().args(Tool.NPM, req("{\"op\":\"test\"}")));
        assertEquals(List.of("run", "lint"), ops().args(Tool.NPM, req("{\"op\":\"run\",\"script\":\"lint\"}")));
        assertTrue(fails(Tool.NPM, "{\"op\":\"run\",\"script\":\"build\"}").contains("no script \"build\""));
        assertTrue(fails(Tool.NPM, "{\"op\":\"run\"}").contains("needs a \"script\""));
    }

    @Test
    void rejectsUnknownOpsFieldsAndOptions() throws Exception {
        assertTrue(fails(Tool.MAVEN, "{\"op\":\"deploy\"}").contains("must be one of clean, compile, install, package, test, verify"));
        assertTrue(fails(Tool.MAVEN, "{\"op\":\"test\",\"args\":[\"-X\"]}").contains("does not take \"args\""));
        assertTrue(fails(Tool.MAVEN, "{\"op\":\"compile\",\"tests\":\"A\"}").contains("does not take \"tests\""));
        assertTrue(fails(Tool.MAVEN, "{\"op\":\"test\",\"tests\":\"-Dexec=x\"}").contains("characters"));
        assertTrue(fails(Tool.MAVEN, "{\"op\":\"test\",\"tests\":\"A B\"}").contains("characters"));
        assertTrue(fails(Tool.MAVEN, "{\"op\":\"test\",\"module\":\"../x\"}").contains("outside"), fails(Tool.MAVEN, "{\"op\":\"test\",\"module\":\"../x\"}"));
        assertTrue(fails(Tool.GRADLE, "{\"op\":\"test\",\"project\":\"app --init-script x\"}").contains("characters"));
        assertTrue(fails(Tool.GRADLE, "{\"op\":\"build\",\"tests\":\"A\"}").contains("does not take"));
        Files.writeString(root.resolve("package.json"), "{\"scripts\":{\"-x\":\"y\"}}");
        assertTrue(fails(Tool.NPM, "{\"op\":\"run\",\"script\":\"-x\"}").contains("characters"));
    }

    @Test
    void timeoutMsIsCheckedAgainstTheMaximum() {
        assertEquals(Duration.ofMinutes(2), CallTimeout.of(req("{}"), Duration.ofMinutes(2), Duration.ofMinutes(10)));
        assertEquals(Duration.ofMinutes(5), CallTimeout.of(req("{\"timeoutMs\":300000}"), Duration.ofMinutes(2), Duration.ofMinutes(10)));
        assertTrue(fails(Tool.MAVEN, "{\"op\":\"test\",\"timeoutMs\":600001}").contains("from 1 to 600000"));
        assertTrue(fails(Tool.MAVEN, "{\"op\":\"test\",\"timeoutMs\":0}").contains("from 1 to 600000"));
        assertTrue(fails(Tool.MAVEN, "{\"op\":\"test\",\"timeoutMs\":\"5m\"}").contains("whole number"));
        ops().check(Tool.MAVEN, req("{\"op\":\"test\",\"timeoutMs\":600000}"));
    }

    @Test
    void untrustedProjectsCantBuild() {
        var e = assertThrows(OperationException.class, () -> ops(false, Map.of()).check(Tool.NPM, req("{\"op\":\"test\"}")));
        assertTrue(e.getMessage().contains("trust this project first"), e.getMessage());
    }

    @Test
    void missingToolsSaySo() {
        var e = assertThrows(OperationException.class, () -> ops(true, Map.of()).check(Tool.MAVEN, req("{\"op\":\"test\"}")));
        assertTrue(e.getMessage().contains("maven is not installed"), e.getMessage());
    }

    @Test
    void wrappersRunInTrustedProjectsAndGradlewMustPinItsChecksum() throws Exception {
        Path mvnw = Files.writeString(root.resolve("mvnw"), "#!/bin/sh\n");
        Files.setPosixFilePermissions(mvnw, PosixFilePermissions.fromString("rwx------"));
        assertEquals(root.resolve("mvnw"), ops().executable(Tool.MAVEN).path());

        Path gradlew = Files.writeString(root.resolve("gradlew"), "#!/bin/sh\n");
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwx------"));
        Path props = Files.createDirectories(root.resolve("gradle/wrapper")).resolve("gradle-wrapper.properties");
        Files.writeString(props, "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.0-bin.zip\n");
        var e = assertThrows(OperationException.class, () -> ops().executable(Tool.GRADLE));
        assertTrue(e.getMessage().contains("distributionSha256Sum"), e.getMessage());
        Files.writeString(props, "distributionSha256Sum=" + "ab".repeat(32) + "\n", java.nio.file.StandardOpenOption.APPEND);
        assertEquals(root.resolve("gradlew"), ops().executable(Tool.GRADLE).path());

        assertEquals(Path.of("/opt/node/lib/npm-cli.js"), ops().executable(Tool.NPM).path());
    }

    @Test
    void cachesAreTheToolsOwnFoldersOnly() {
        assertEquals(List.of(home.resolve(".m2/repository"), home.resolve(".m2/wrapper")), ops().caches(Tool.MAVEN));
        assertEquals(List.of(home.resolve(".npm")), ops().caches(Tool.NPM));
        List<Path> g = ops().caches(Tool.GRADLE);
        assertTrue(g.contains(home.resolve(".gradle/caches")) && g.contains(home.resolve(".gradle/wrapper")), g.toString());
        assertFalse(g.contains(home.resolve(".gradle")) || g.contains(home.resolve(".gradle/init.d")), g.toString());
    }

    @Test
    void toolsArePointedAtTheProxyWithoutArgv() {
        var route = new ChildSandbox.ProxyRoute(4321, tmp.resolve("net/proxy.sock"), "jharness", "s3cr3t");
        Map<String, String> npm = ops().env(Tool.NPM, ops().executable(Tool.NPM), route, tmp);
        assertEquals("http://jharness:s3cr3t@127.0.0.1:4321", npm.get("npm_config_https_proxy"));
        assertEquals("http://jharness:s3cr3t@127.0.0.1:4321", npm.get("npm_config_proxy"));
        assertEquals("false", npm.get("npm_config_update_notifier"));
        assertTrue(npm.get("PATH").startsWith("/opt/node/bin:"), npm.get("PATH"));

        String jto = ops().env(Tool.GRADLE, ops().executable(Tool.GRADLE), route, tmp).get("JAVA_TOOL_OPTIONS");
        assertTrue(jto.contains("-Dhttps.proxyHost=127.0.0.1") && jto.contains("-Dhttps.proxyPort=4321")
                && jto.contains("-Dhttps.proxyPassword=s3cr3t") && jto.contains("-Djdk.http.auth.tunneling.disabledSchemes=")
                && jto.contains("-Djava.io.tmpdir=" + tmp) && jto.contains("-Djava.net.preferIPv4Stack=true"), jto);

        String xml = BuildOperations.mavenSettings(route);
        assertTrue(xml.contains("<protocol>https</protocol><host>127.0.0.1</host><port>4321</port><username>jharness</username><password>s3cr3t</password>"), xml);
        assertFalse(ops().args(Tool.MAVEN, req("{\"op\":\"test\"}"), Path.of("/s.xml")).toString().contains("s3cr3t"));
    }

    @Test
    void offlineModeRunsToolsOffline() {
        var exes = Map.of(Tool.MAVEN, new Executable("mvn", Path.of("/opt/maven/bin/mvn")),
                Tool.GRADLE, new Executable("gradle", Path.of("/opt/gradle/bin/gradle")),
                Tool.NPM, new Executable("npm", Path.of("/opt/node/lib/npm-cli.js"), Path.of("/opt/node/bin")));
        BuildOperations off = new BuildOperations(runner, ws, Timeouts.defaults(), true, exes, home, null, true);
        assertTrue(off.args(Tool.MAVEN, req("{\"op\":\"test\"}")).contains("-o"));
        assertTrue(off.args(Tool.GRADLE, req("{\"op\":\"test\"}")).contains("--offline"));
        var route = new ChildSandbox.ProxyRoute(4321, tmp.resolve("net/proxy.sock"), "jharness", "s3cr3t");
        assertEquals("true", off.env(Tool.NPM, off.executable(Tool.NPM), route, tmp).get("npm_config_offline"));
        assertDoesNotThrow(() -> ProcessRunner.checkEnv(off.env(Tool.NPM, off.executable(Tool.NPM), route, tmp)));
        assertFalse(ops().args(Tool.MAVEN, req("{\"op\":\"test\"}")).contains("-o"));
        assertFalse(ops().args(Tool.GRADLE, req("{\"op\":\"test\"}")).contains("--offline"));
        assertNull(ops().env(Tool.NPM, ops().executable(Tool.NPM), route, tmp).get("npm_config_offline"));
    }

    /** Spec 023: typed build parameters. */
    @Test
    void mavenParameters() {
        List<String> base = List.of("-B", "-ntp", "-Dstyle.color=never");
        assertEquals(cat(base, "-DskipTests", "-P", "dev,!ci", "-T", "1C", "install"),
                ops().args(Tool.MAVEN, req("{\"op\":\"install\",\"skipTests\":true,\"profiles\":\"dev,!ci\",\"threads\":\"1C\"}")));
        assertEquals(cat(base, "-DskipTests", "package"), ops().args(Tool.MAVEN, req("{\"op\":\"package\",\"skipTests\":true}")));
        assertEquals(cat(base, "package"), ops().args(Tool.MAVEN, req("{\"op\":\"package\",\"skipTests\":false}")));
        assertEquals(cat(base, "-T", "4", "verify"), ops().args(Tool.MAVEN, req("{\"op\":\"verify\",\"threads\":\"4\"}")));
        assertTrue(fails(Tool.MAVEN, "{\"op\":\"test\",\"skipTests\":true}").contains("does not take \"skipTests\""));
        assertTrue(fails(Tool.MAVEN, "{\"op\":\"verify\",\"tests\":\"A\",\"skipTests\":true}").contains("can't be used together"));
        assertTrue(fails(Tool.MAVEN, "{\"op\":\"package\",\"skipTests\":\"true\"}").contains("must be true or false"));
        for (String bad : List.of("-Dexec.args=x", "dev ci", "dev,", "a/b", "dev;rm")) {
            assertTrue(fails(Tool.MAVEN, "{\"op\":\"package\",\"profiles\":\"" + bad + "\"}").contains("characters"), bad);
        }
        for (String bad : List.of("0", "33", "5C", "1C -X", "-1")) {
            assertTrue(fails(Tool.MAVEN, "{\"op\":\"package\",\"threads\":\"" + bad + "\"}").contains("characters"), bad);
        }
        assertTrue(fails(Tool.MAVEN, "{\"op\":\"clean\",\"profiles\":\"dev\"}").contains("does not take"));
    }

    @Test
    void gradleAndNpmParameters() throws Exception {
        List<String> base = List.of("--console=plain", "--no-daemon");
        assertEquals(cat(base, ":app:build", "-x", "test", "--parallel", "--continue"),
                ops().args(Tool.GRADLE, req("{\"op\":\"build\",\"project\":\":app\",\"skipTests\":true,\"parallel\":true,\"continue\":true}")));
        assertEquals(cat(base, "check", "-x", "test"), ops().args(Tool.GRADLE, req("{\"op\":\"check\",\"skipTests\":true}")));
        assertTrue(fails(Tool.GRADLE, "{\"op\":\"test\",\"skipTests\":true}").contains("does not take"));
        assertTrue(fails(Tool.GRADLE, "{\"op\":\"assemble\",\"skipTests\":true}").contains("does not take"));
        assertTrue(fails(Tool.GRADLE, "{\"op\":\"build\",\"parallel\":1}").contains("must be true or false"));
        assertEquals(List.of("ci", "--ignore-scripts", "--omit=dev"), ops().args(Tool.NPM, req("{\"op\":\"ci\",\"omitDev\":true}")));
        assertEquals(List.of("install", "--ignore-scripts", "--omit=dev"), ops().args(Tool.NPM, req("{\"op\":\"install\",\"omitDev\":true}")));
        assertTrue(fails(Tool.NPM, "{\"op\":\"test\",\"omitDev\":true}").contains("does not take"));
    }

    @Test
    void approvalSummaryShowsTheParameters() {
        assertEquals("maven package · skipTests, profiles dev",
                BuildOperations.summary(Tool.MAVEN, req("{\"op\":\"package\",\"skipTests\":true,\"profiles\":\"dev\"}")));
        assertEquals("gradle build · project :app, parallel",
                BuildOperations.summary(Tool.GRADLE, req("{\"op\":\"build\",\"project\":\":app\",\"parallel\":true,\"continue\":false}")));
        assertEquals("npm test", BuildOperations.summary(Tool.NPM, req("{\"op\":\"test\"}")));
        assertEquals("{\"tool\":\"maven\",\"op\":\"maven package\"}",
                Json.write(BuildOperations.detail(Tool.MAVEN, req("{\"op\":\"package\",\"skipTests\":true}"))), "\"always\" saves the operation alone");
    }

    private static List<String> cat(List<String> a, String... b) {
        List<String> out = new java.util.ArrayList<>(a);
        out.addAll(List.of(b));
        return out;
    }

    @Test
    void permissionDetailNamesToolAndOp() {
        assertEquals("{\"tool\":\"maven\",\"op\":\"maven test\"}", Json.write(BuildOperations.detail(Tool.MAVEN, req("{\"op\":\"test\"}"))));
    }

    @Test
    void noProxyNoBuild() throws Exception {
        assumeTrue(runner.sandbox().available());
        Files.writeString(root.resolve("package.json"), "{\"scripts\":{\"test\":\"node -e 1\"}}");
        var e = assertThrows(OperationException.class, () -> ops().run(Tool.NPM, req("{\"op\":\"test\"}")));
        assertTrue(e.getMessage().contains("egress proxy"), e.getMessage());
    }
}
