package dev.jharness.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretsTest {

    @TempDir
    Path home;

    private Path write(String text, String perms) throws Exception {
        Path f = Secrets.fileFor(home);
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString(perms));
        return f;
    }

    @Test
    void environmentFirstThenFile() throws Exception {
        write("# keys\nANTHROPIC_API_KEY = sk-file\n\nOTHER=x=y\n", "rw-------");
        Secrets s = Secrets.load(home, Map.of("ANTHROPIC_API_KEY", "sk-env")::get);
        assertEquals("sk-env", s.apply("ANTHROPIC_API_KEY"));
        assertEquals("x=y", s.apply("OTHER"));
        assertNull(s.apply("MISSING"));
        Secrets fileOnly = Secrets.load(home, k -> null);
        assertEquals("sk-file", fileOnly.apply("ANTHROPIC_API_KEY"));
        assertFalse(fileOnly.toString().contains("sk-file"));
    }

    @Test
    void missingFileIsFine() throws Exception {
        assertNull(Secrets.load(home, k -> null).apply("X"));
    }

    @Test
    void readableFileIsRefused() throws Exception {
        for (String perms : new String[] {"rw-r--r--", "rw-rw----", "rwx------", "r--------"}) {
            write("K=v\n", perms);
            ConfigException e = assertThrows(ConfigException.class, () -> Secrets.load(home, k -> null), perms);
            assertTrue(e.getMessage().contains("chmod 600"), e.getMessage());
        }
    }

    @Test
    void symlinkAndBadLinesAreRefused() throws Exception {
        Path real = home.resolve("real");
        Files.writeString(real, "K=v\n");
        Files.setPosixFilePermissions(real, PosixFilePermissions.fromString("rw-------"));
        Files.createDirectories(home.resolve(".jharness"));
        Files.createSymbolicLink(Secrets.fileFor(home), real);
        assertTrue(assertThrows(ConfigException.class, () -> Secrets.load(home, k -> null)).getMessage().contains("regular file"));
        Files.delete(Secrets.fileFor(home));
        write("no equals sign\n", "rw-------");
        assertTrue(assertThrows(ConfigException.class, () -> Secrets.load(home, k -> null)).getMessage().contains("line 1"));
    }
}
