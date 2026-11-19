package dev.jharness.process;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.events.EventBus;
import dev.jharness.events.Origin;
import dev.jharness.security.Workspace;

class SearchTest {

    @TempDir
    Path tmp;
    Workspace ws;

    @BeforeEach
    void setUp() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("ws"));
        Files.createDirectories(root.resolve("src/main"));
        Files.writeString(root.resolve("src/main/App.java"), "class App {\n  // TODO fix\n  void run() {}\n}\n");
        Files.writeString(root.resolve("src/main/Util.java"), "class Util { /* todo later */ }\n");
        Files.writeString(root.resolve("README.md"), "# Readme\nTODO: docs\n");
        Files.createDirectories(root.resolve(".hidden"));
        Files.writeString(root.resolve(".hidden/secret.txt"), "TODO hidden\n");
        Files.write(root.resolve("bin.dat"), new byte[] {'T', 'O', 'D', 'O', 0, 1, 2});
        Files.createDirectories(root.resolve("keys"));
        Files.writeString(root.resolve("keys/server.pem"), "TODO secret key\n");
        ws = new Workspace(root);
    }

    private static void sameShape(Search s, Workspace ws) {
        var m = s.grep("TODO", ws.root(), null, false, 100);
        assertEquals(List.of("README.md:2", "src/main/App.java:2"),
                java.util.stream.StreamSupport.stream(m.get("matches").spliterator(), false)
                        .map(n -> n.get("path").asText() + ":" + n.get("line").asInt()).toList(), s.engine());
        assertEquals("  // TODO fix", m.get("matches").get(1).get("text").asText());
        assertFalse(m.get("truncated").asBoolean());
        var ci = s.grep("todo", ws.resolve("src"), "*.java", true, 100);
        assertEquals(2, ci.get("matches").size(), s.engine());
        var one = s.grep("TODO", ws.root(), null, false, 1);
        assertEquals(1, one.get("matches").size());
        assertTrue(one.get("truncated").asBoolean());
        var files = s.listFiles(ws.root(), null, 100);
        assertEquals("[\"README.md\",\"src/main/App.java\",\"src/main/Util.java\"]".replace("\"README.md\",", "\"README.md\",\"bin.dat\","),
                files.get("files").toString(), s.engine());
        assertEquals("[\"src/main/App.java\",\"src/main/Util.java\"]", s.listFiles(ws.resolve("src"), "*.java", 100).get("files").toString());
        var lim = s.listFiles(ws.root(), null, 2);
        assertEquals(2, lim.get("files").size());
        assertTrue(lim.get("truncated").asBoolean());
    }

    @Test
    void javaFallback() {
        sameShape(new JavaSearch(ws, new dev.jharness.security.DenyList(List.of())), ws);
        assertThrows(OperationException.class, () -> new JavaSearch(ws).grep("(", ws.root(), null, false, 10));
    }

    private Optional<Executable> rg() {
        return Executables.resolve("rg", ws.root());
    }

    @Test
    void ripgrepSameResults() {
        assumeTrue(rg().isPresent(), "rg not installed; CI covers this path");
        RipgrepSearch s = new RipgrepSearch(new ProcessRunner(new EventBus("s"), () -> Origin.main(1), 30_000, TestSandboxes.in(ws.root())), rg().get(), ws, Duration.ofSeconds(60),
                new dev.jharness.security.DenyList(List.of()));
        sameShape(s, ws);
    }

    @Test
    void ripgrepArgumentsNeverCarryBlockedFlags() {
        Executable fake = new Executable("rg", Path.of("/nonexistent/rg"));
        RipgrepSearch s = new RipgrepSearch(new ProcessRunner(new EventBus("s"), () -> Origin.main(1), 30_000, TestSandboxes.in(ws.root())), fake, ws, Duration.ofSeconds(60));
        List<String> a = s.grepArgs("--pre=sh", ws.root(), "--follow", false);
        int e = a.indexOf("-e");
        assertEquals("--pre=sh", a.get(e + 1), "the pattern is a value of -e");
        assertEquals("--follow", a.get(a.indexOf("-g") + 1), "the glob is a value of -g");
        assertEquals("--", a.get(a.size() - 2));
        assertTrue(a.contains("--no-config"));
        for (int i = 0; i < a.indexOf("--"); i++) {
            boolean value = i > 0 && (a.get(i - 1).equals("-e") || a.get(i - 1).equals("-g"));
            if (!value) {
                assertFalse(RipgrepSearch.BLOCKED.contains(a.get(i)), a.get(i));
            }
        }
        List<String> l = s.listArgs(ws.resolve("src"), null);
        assertEquals(List.of("--files", "--no-config", "--sort=path", "--no-messages"), l.subList(0, 4));
        assertEquals(List.of("--", "src"), l.subList(l.size() - 2, l.size()));
        assertTrue(l.contains("!*.pem") && l.get(l.indexOf("!*.pem") - 1).equals("-g"), "deny globs become -g exclusions");
    }
}
