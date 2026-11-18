package dev.jharness.testkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.jharness.process.Executable;
import dev.jharness.process.Executables;

/**
 * A small git repository with a seeded bug, for the P1 gate scenario: {@code Calculator.add}
 * subtracts. Test infrastructure; git is run directly here only to set the repository up.
 */
public final class SampleRepo {

    public static final String CALCULATOR = "src/main/java/demo/Calculator.java";
    public static final String BUGGY_LINE = "return a - b;";
    public static final String FIXED_LINE = "return a + b;";
    public static final String TASK = "Calculator.add returns the wrong result: add(2, 3) should be 5. "
            + "Find the bug, fix it, and commit the fix with a short message.";

    private SampleRepo() {}

    public static Path create(Path dir) throws IOException, InterruptedException {
        Files.createDirectories(dir.resolve("src/main/java/demo"));
        Files.createDirectories(dir.resolve("src/test/java/demo"));
        Files.writeString(dir.resolve(CALCULATOR), """
                package demo;

                /** Basic arithmetic. */
                public final class Calculator {

                    public int add(int a, int b) {
                        %s
                    }

                    public int multiply(int a, int b) {
                        return a * b;
                    }
                }
                """.formatted(BUGGY_LINE));
        Files.writeString(dir.resolve("src/test/java/demo/CalculatorTest.java"), """
                package demo;

                class CalculatorTest {
                    void add() {
                        assert new Calculator().add(2, 3) == 5;
                    }
                }
                """);
        Files.writeString(dir.resolve("README.md"), "# demo\n\nA calculator.\n");
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.email", "sample@example.invalid");
        git(dir, "config", "user.name", "Sample");
        git(dir, "add", ".");
        git(dir, "commit", "-q", "-m", "Initial commit");
        return dir;
    }

    /** The commit subjects, newest first. */
    public static List<String> log(Path dir) throws IOException, InterruptedException {
        return List.of(git(dir, "log", "--format=%s").split("\n"));
    }

    public static boolean fixed(Path dir) throws IOException {
        String text = Files.readString(dir.resolve(CALCULATOR));
        return text.contains(FIXED_LINE) && !text.contains(BUGGY_LINE);
    }

    private static String git(Path dir, String... args) throws IOException, InterruptedException {
        Executable exe = Executables.resolve("git", dir).orElseThrow(() -> new IllegalStateException("git not found"));
        List<String> cmd = new ArrayList<>(List.of(exe.path().toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
        return out.strip();
    }

    /** A recorded run of the gate scenario: search, read, fix, diff, commit, summary. */
    public static List<String> gateReplies() {
        return List.of(
                "I'll find the add method.\n```js\nJSON.parse(api.grep({pattern: \"int add\\\\(\", glob: \"*.java\"})).matches\n```",
                "```js\napi.readFile(\"" + CALCULATOR + "\")\n```",
                "`add` subtracts. Fixing it:\n```js\napi.editFile(\"" + CALCULATOR + "\", \"" + BUGGY_LINE + "\", \"" + FIXED_LINE
                        + "\");\nJSON.parse(api.git({op: \"diff\"})).output\n```",
                "```js\napi.git({op: \"add\", paths: [\"" + CALCULATOR + "\"]});\nconst c = JSON.parse(api.git({op: \"commit\", message: \"Fix Calculator.add to add\"}));\nc.exitCode === 0 ? \"committed\" : c.output\n```",
                "Fixed `Calculator.add`: it returned `a - b`; it now returns `a + b`. Committed as \"Fix Calculator.add to add\".");
    }
}
