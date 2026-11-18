package dev.jharness.security;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilePolicyTest {

    @TempDir
    Path tmp;
    Path ws;
    Path docs;
    FilePolicy policy;

    @BeforeEach
    void setUp() throws Exception {
        ws = Files.createDirectories(tmp.resolve("ws"));
        docs = Files.createDirectories(tmp.resolve("docs"));
        Files.writeString(docs.resolve("guide.md"), "g");
        Files.writeString(docs.resolve(".env"), "SECRET=1");
        Files.writeString(ws.resolve("App.java"), "a");
        policy = new FilePolicy(new Workspace(ws), List.of(docs.toRealPath()), List.of("secrets/**", "*.p12"));
    }

    @Test
    void mountsAreReadOnly() throws Exception {
        String guide = docs.toRealPath().resolve("guide.md").toString();
        FilePolicy.Target t = policy.read(guide);
        assertTrue(t.mounted());
        assertEquals(guide, t.display());
        Workspace.PathException e = assertThrows(Workspace.PathException.class, () -> policy.write(guide));
        assertTrue(e.getMessage().contains("read-only"));
        assertThrows(Workspace.PathException.class, () -> policy.read(docs.toRealPath().resolve("../ws/App.java").toString() + "/../../../etc/passwd"));
    }

    @Test
    void denyListAppliesToReadsAndWrites() throws Exception {
        for (String p : List.of(".env", ".env.local", "config/.env", "certs/server.pem", "ssh/id_rsa", "ssh/id_ed25519.pub",
                "deploy.key", ".git/config", ".git", "sub/.git/HEAD", "secrets/db.txt", "secrets", "keystore.p12")) {
            assertThrows(Workspace.PathException.class, () -> policy.read(p), p);
            assertThrows(Workspace.PathException.class, () -> policy.write(p), p);
        }
        assertThrows(Workspace.PathException.class, () -> policy.read(docs.toRealPath().resolve(".env").toString()));
        assertEquals("App.java", policy.read("App.java").display());
        assertEquals("src/env.txt", policy.write("src/env.txt").display());
        assertEquals(".gitignore", policy.write(".gitignore").display());
    }

    @Test
    void projectConfigurationCantBeWritten() {
        assertThrows(Workspace.PathException.class, () -> policy.write(".jharness/settings.json"));
        assertThrows(Workspace.PathException.class, () -> policy.write(".jharness"));
        assertEquals(".jharness/settings.json", policy.read(".jharness/settings.json").display());
    }

    @Test
    void hiddenResults() throws Exception {
        assertTrue(policy.hidden("config/.env"));
        assertTrue(policy.hidden(docs.toRealPath().resolve(".env").toString()));
        assertFalse(policy.hidden("src/App.java"));
        assertFalse(policy.hidden(docs.toRealPath().resolve("guide.md").toString()));
    }

    @Test
    void builtInsCantBeRemoved() {
        FilePolicy none = new FilePolicy(new Workspace(ws), List.of(), List.of());
        assertThrows(Workspace.PathException.class, () -> none.read(".env"));
        assertTrue(none.denyList().globs().containsAll(DenyList.BUILT_IN));
    }
}
