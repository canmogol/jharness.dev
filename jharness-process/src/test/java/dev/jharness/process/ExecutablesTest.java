package dev.jharness.process;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutablesTest {

    @TempDir
    Path tmp;

    @Test
    void onlyKnownToolsResolve() {
        assertThrows(IllegalArgumentException.class, () -> Executables.resolve("sh", "/bin", tmp));
        assertThrows(IllegalArgumentException.class, () -> Executables.resolve("npx", "/usr/bin", tmp));
    }

    @Test
    void skipsWorkspaceAndRelativePathEntries() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path planted = Files.createDirectories(ws.resolve("bin")).resolve("git");
        Files.writeString(planted, "#!/bin/sh\necho planted\n");
        Files.setPosixFilePermissions(planted, PosixFilePermissions.fromString("rwx------"));
        Path real = Executables.resolve("git", ws).orElseThrow().path();
        String path = ws.resolve("bin") + ":bin:" + real.getParent();
        Executable git = Executables.resolve("git", path, ws).orElseThrow();
        assertEquals(real, git.path());
        assertTrue(git.path().isAbsolute());
        assertTrue(Executables.resolve("git", ws.resolve("bin") + ":bin", ws).isEmpty());
    }

    @Test
    void missingToolIsEmpty() {
        assertTrue(Executables.resolve("rg", tmp.toString(), tmp).isEmpty());
        assertTrue(Executables.resolve("rg", null, tmp).isEmpty());
    }
}
