package dev.jharness.security;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrustStoreTest {

    @TempDir
    Path tmp;

    @Test
    void recordsAnswersByRealPath() throws Exception {
        Path home = tmp.resolve("home");
        Path a = Files.createDirectories(tmp.resolve("a"));
        Path b = Files.createDirectories(tmp.resolve("b"));
        Path link = Files.createSymbolicLink(tmp.resolve("link"), a);
        TrustStore s = TrustStore.load(home);
        assertEquals(Optional.empty(), s.trusted(a));
        s.record(a, true);
        s.record(b, false);
        TrustStore again = TrustStore.load(home);
        assertEquals(Optional.of(true), again.trusted(a));
        assertEquals(Optional.of(true), again.trusted(link), "same folder through a link");
        assertEquals(Optional.of(false), again.trusted(b));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(TrustStore.fileFor(home))));
    }
}
