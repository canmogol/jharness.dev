package dev.jharness.security;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceTest {

    @TempDir
    Path tmp;
    Path ws;
    Path outside;
    Workspace w;

    @BeforeEach
    void setUp() throws Exception {
        ws = Files.createDirectories(tmp.resolve("ws"));
        outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "s");
        Files.createDirectories(ws.resolve("src"));
        Files.writeString(ws.resolve("src/A.java"), "a");
        w = new Workspace(ws);
    }

    @Test
    void acceptsPathsInside() {
        Path real = w.root();
        assertEquals(real.resolve("src/A.java"), w.resolve("src/A.java"));
        assertEquals(real.resolve("src/A.java"), w.resolve("./src/../src/A.java"));
        assertEquals(real.resolve("new/dir/B.java"), w.resolve("new/dir/B.java"));
        assertEquals(real.resolve("src/A.java"), w.resolve(real.resolve("src/A.java").toString()));
        assertEquals(real, w.resolve("."));
        assertEquals("src/A.java", w.relative(w.resolve("src/A.java")));
    }

    @Test
    void rejectsAbsolutePathsOutside() {
        assertThrows(Workspace.PathException.class, () -> w.resolve("/etc/passwd"));
        assertThrows(Workspace.PathException.class, () -> w.resolve(outside.resolve("secret.txt").toString()));
    }

    @Test
    void rejectsDotDotEscapes() {
        assertThrows(Workspace.PathException.class, () -> w.resolve("../outside/secret.txt"));
        assertThrows(Workspace.PathException.class, () -> w.resolve("src/../../outside/secret.txt"));
        assertThrows(Workspace.PathException.class, () -> w.resolve(".."));
    }

    @Test
    void rejectsSymlinkEscapes() throws Exception {
        Files.createSymbolicLink(ws.resolve("link-file"), outside.resolve("secret.txt"));
        Files.createSymbolicLink(ws.resolve("link-dir"), outside);
        Files.createSymbolicLink(ws.resolve("dangling"), tmp.resolve("nope"));
        assertThrows(Workspace.PathException.class, () -> w.resolve("link-file"));
        assertThrows(Workspace.PathException.class, () -> w.resolve("link-dir/secret.txt"));
        assertThrows(Workspace.PathException.class, () -> w.resolve("link-dir/new.txt"), "write through a symlinked folder");
        assertThrows(Workspace.PathException.class, () -> w.resolve("dangling"));
    }

    @Test
    void symlinkInsideIsFollowed() throws Exception {
        Files.createSymbolicLink(ws.resolve("alias"), ws.resolve("src"));
        assertEquals(w.root().resolve("src/A.java"), w.resolve("alias/A.java"));
    }

    @Test
    void rejectsEmptyAndNul() {
        assertThrows(Workspace.PathException.class, () -> w.resolve(""));
        assertThrows(Workspace.PathException.class, () -> w.resolve(null));
        assertThrows(Workspace.PathException.class, () -> w.resolve("a\0b"));
    }
}
