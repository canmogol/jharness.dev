package dev.jharness.cli;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import dev.jharness.process.Executables;
import dev.jharness.process.ToolDefinition;
import dev.jharness.process.ToolDefinitions;
import dev.jharness.security.TrustStore;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
 * {@code jharness tools [list|show NAME|check|reset [NAME]]} (spec 028, D-066). Exit codes: 0; 1 when
 * {@code check} finds a broken file; 2 for a bad argument.
 */
@Command(name = "tools", mixinStandardHelpOptions = true,
        description = {"List, show, check or reset the tool definitions in ~/.jharness/tools/ and the project's .jharness/tools/.",
                "list (default): each tool, where it comes from and whether it runs here.",
                "show NAME: the definition in use. check: validate every file. reset [NAME]: write the shipped default again."})
final class ToolsCommand implements Callable<Integer> {

    @ParentCommand
    Main parent;

    @Parameters(arity = "0..2", paramLabel = "ACTION", description = "list, show NAME, check or reset [NAME]")
    List<String> action = List.of();

    @Option(names = "--cwd", paramLabel = "DIR", description = "Workspace folder (default: the current folder).")
    Path cwd;

    @Option(names = "--home", hidden = true, paramLabel = "DIR", description = "Tests only: use DIR instead of the home folder.")
    Path home;

    @Override
    public Integer call() {
        PrintStream out = parent.out;
        PrintStream err = parent.err;
        Path h = home != null ? home : parent.home != null ? parent.home : Path.of(System.getProperty("user.home"));
        Path c = cwd != null ? cwd : parent.cwd;
        Path ws = (c != null ? c : Path.of("")).toAbsolutePath().normalize();
        String verb = action.isEmpty() ? "list" : action.getFirst();
        String arg = action.size() > 1 ? action.get(1) : null;
        List<Path> seeded = ToolDefinitions.seed(h);
        if (!seeded.isEmpty()) {
            out.println("Wrote the shipped tools to " + TerminalSafe.escape(ToolDefinitions.userFolder(h).toString()) + ".");
        }
        boolean trusted = Files.isDirectory(ws) && TrustStore.load(h).trusted(ws).orElse(false);
        switch (verb) {
            case "list" -> {
                if (arg != null) {
                    return usage(err);
                }
                ToolDefinitions t = ToolDefinitions.load(h, ws, trusted);
                if (t.tools().isEmpty() && t.errors().isEmpty()) {
                    out.println("No tools. Add NAME.json files to " + TerminalSafe.escape(ToolDefinitions.userFolder(h).toString())
                            + ", or run jharness tools reset to write the shipped ones.");
                }
                for (ToolDefinition d : t.tools().values()) {
                    String where = d.source() == ToolDefinition.Source.PROJECT ? "project" : "user";
                    boolean found = Executables.resolve(d.executable(), ws).isPresent();
                    out.println(TerminalSafe.escape(d.name() + "  " + where + "  " + d.executable() + (found ? "" : " (not found on PATH)") + "  "
                            + String.join(", ", d.operations().keySet())
                            + (d.source() == ToolDefinition.Source.USER && ToolDefinitions.differsFromDefault(h, d.name()) ? "  (differs from the shipped default)" : "")));
                }
                t.errors().forEach((n, e) -> out.println(TerminalSafe.escape(n + "  broken: " + e)));
                if (!trusted && !ToolDefinitions.describeProject(h, ws).isEmpty()) {
                    out.println("This project defines tools in .jharness/tools/; they count once you trust the project.");
                }
                return 0;
            }
            case "show" -> {
                if (arg == null || action.size() > 2) {
                    return usage(err);
                }
                ToolDefinitions t = ToolDefinitions.load(h, ws, trusted);
                ToolDefinition d = t.get(arg);
                if (d == null) {
                    if (ToolDefinitions.DEFAULTS.contains(arg)) {
                        out.println("# " + arg + " is not in use; the shipped default:");
                        out.print(TerminalSafe.text(ToolDefinitions.defaultText(arg)));
                        return 0;
                    }
                    err.println("jharness tools: no tool " + TerminalSafe.escape(arg) + (t.errors().containsKey(arg) ? ": " + TerminalSafe.escape(t.errors().get(arg)) : ""));
                    return 2;
                }
                Path f = d.source() == ToolDefinition.Source.PROJECT ? ToolDefinitions.projectFolder(ws).resolve(arg + ".json")
                        : ToolDefinitions.userFolder(h).resolve(arg + ".json");
                out.println("# " + TerminalSafe.escape(f.toString()));
                try {
                    out.print(TerminalSafe.text(Files.readString(f)));
                } catch (java.io.IOException e) {
                    err.println("jharness tools: cannot read " + TerminalSafe.escape(f.toString()) + ": " + e.getMessage());
                    return 2;
                }
                return 0;
            }
            case "check" -> {
                if (arg != null) {
                    return usage(err);
                }
                ToolDefinitions user = ToolDefinitions.load(h, ws, false);
                int broken = user.errors().size();
                user.errors().values().forEach(e -> out.println(TerminalSafe.escape(e)));
                for (var p : ToolDefinitions.describeProject(h, ws)) {
                    if (p.error() != null) {
                        broken++;
                        out.println(TerminalSafe.escape(p.error()));
                    }
                }
                out.println(broken == 0 ? "All tool files are valid." : broken + " tool file(s) have problems.");
                return broken == 0 ? 0 : 1;
            }
            case "reset" -> {
                if (action.size() > 2) {
                    return usage(err);
                }
                List<String> names = arg == null ? ToolDefinitions.DEFAULTS : List.of(arg);
                for (String n : names) {
                    if (!ToolDefinitions.DEFAULTS.contains(n)) {
                        err.println("jharness tools: " + TerminalSafe.escape(n) + " is not a shipped tool (shipped: " + String.join(", ", ToolDefinitions.DEFAULTS) + ")");
                        return 2;
                    }
                    out.println("Wrote " + TerminalSafe.escape(ToolDefinitions.reset(h, n).toString()) + ".");
                }
                return 0;
            }
            default -> {
                return usage(err);
            }
        }
    }

    private static int usage(PrintStream err) {
        err.println("jharness tools: usage: jharness tools [list | show NAME | check | reset [NAME]]");
        return 2;
    }
}
