package dev.jharness.testkit;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Constitution quality gate: no {@code ProcessBuilder} or {@code Runtime.exec} outside
 * {@code ProcessRunner}, and no {@code HttpClient} or socket outside {@code HttpClients} (AC-01). The
 * one exception is {@code ProxyBridge} (spec 013, D-049): it runs inside the Linux sandbox, starts the
 * argv {@code ProcessRunner} gave it and connects only to the egress proxy's Unix socket. The web viewer's
 * {@code SessionViewer} may listen on the loopback address (spec 048, D-053, D-083); it opens no outbound connection.
 * Scans the main sources of every product module; test code and this testkit are not product code.
 */
class ArchitectureTest {

    static final String BRIDGE = "jharness-process/src/main/java/dev/jharness/process/ProxyBridge.java";

    record Rule(String name, Pattern pattern, List<String> allowedFiles) {

        String allowedFile() {
            return allowedFiles.getFirst();
        }
    }

    static final List<Rule> RULES = List.of(
            new Rule("process start", Pattern.compile("new\\s+ProcessBuilder\\b|Runtime\\s*\\.\\s*getRuntime\\s*\\(\\s*\\)\\s*\\.\\s*exec|\\.exec\\s*\\(\\s*new\\s+String"),
                    List.of("jharness-process/src/main/java/dev/jharness/process/ProcessRunner.java", BRIDGE)),
            new Rule("HTTP client", Pattern.compile("HttpClient\\s*\\.\\s*(newBuilder|newHttpClient)\\b"),
                    List.of("jharness-net/src/main/java/dev/jharness/net/http/HttpClients.java")),
            new Rule("socket", Pattern.compile("new\\s+(Server)?Socket\\s*\\(|(Server)?SocketChannel\\s*\\.\\s*open|new\\s+DatagramSocket|HttpServer\\s*\\.\\s*create"),
                    List.of("jharness-net/src/main/java/dev/jharness/net/EgressProxy.java", BRIDGE,
                            "jharness-web/src/main/java/dev/jharness/web/SessionViewer.java")));

    static Path root() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("jharness-events"))) {
            p = p.getParent();
        }
        return p;
    }

    @Test
    void sideEffectsStayInTheirModules() throws IOException {
        Path root = root();
        assertNotNull(root);
        List<String> violations = new ArrayList<>();
        int scanned = 0;
        for (String module : List.of("jharness-events", "jharness-config", "jharness-security", "jharness-process", "jharness-net", "jharness-sandbox", "jharness-llm", "jharness-agent", "jharness-web", "jharness-cli")) {
            Path src = root.resolve(module).resolve("src/main/java");
            try (Stream<Path> files = Files.walk(src)) {
                for (Path f : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                    scanned++;
                    String rel = root.relativize(f).toString().replace('\\', '/');
                    String text = Files.readString(f);
                    for (Rule r : RULES) {
                        if (r.pattern().matcher(text).find() && !r.allowedFiles().contains(rel)) {
                            violations.add(r.name() + " in " + rel);
                        }
                    }
                }
            }
        }
        assertTrue(scanned > 30, "scanned only " + scanned + " files");
        assertEquals(List.of(), violations);
    }

    @Test
    void rulesCatchViolations() {
        assertTrue(RULES.get(0).pattern().matcher("var p = new ProcessBuilder(cmd);").find());
        assertTrue(RULES.get(0).pattern().matcher("Runtime.getRuntime().exec(\"ls\")").find());
        assertTrue(RULES.get(1).pattern().matcher("HttpClient.newHttpClient()").find());
        assertTrue(RULES.get(2).pattern().matcher("new Socket(\"h\", 80)").find());
        assertTrue(RULES.get(2).pattern().matcher("var s = new ServerSocket(0);").find());
        assertTrue(RULES.get(2).pattern().matcher("SocketChannel.open(addr)").find());
        // Sockets are allowed only in the egress proxy (spec 011 AC-08); anywhere else is a violation.
        assertEquals("jharness-net/src/main/java/dev/jharness/net/EgressProxy.java", RULES.get(2).allowedFile());
        assertNotEquals("jharness-llm/src/main/java/dev/jharness/llm/OpenAiCompatibleProvider.java", RULES.get(2).allowedFile());
        assertEquals(List.of("jharness-net/src/main/java/dev/jharness/net/EgressProxy.java", BRIDGE,
                "jharness-web/src/main/java/dev/jharness/web/SessionViewer.java"), RULES.get(2).allowedFiles(), "the viewer listens on loopback (D-083)");
        assertTrue(RULES.get(2).pattern().matcher("HttpServer.create(addr, 0)").find());
        assertEquals(List.of(), RULES.get(1).allowedFiles().stream().filter(f -> f.equals(BRIDGE)).toList());
    }
}
