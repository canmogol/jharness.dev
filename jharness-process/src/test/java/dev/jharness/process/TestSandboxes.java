package dev.jharness.process;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** The real OS sandbox for tests, with a fresh private temp folder. */
final class TestSandboxes {

    private TestSandboxes() {}

    static ChildSandbox in(Path workspace) {
        try {
            Path t = Files.createTempDirectory("jharness-test-");
            return ChildSandbox.detect(ChildSandbox.Policy.forSession(workspace, t, Path.of(System.getProperty("user.home"))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
