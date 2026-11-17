package dev.jharness.spike;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spike entry point: {@code spike <isolate|limits|model|jline|all> [--config FILE] [--set P.KEY=VALUE]... [args...]}.
 *
 * <p>Every result is one JSON object per line on stdout, so CI can collect them. Probes not yet
 * written report {@code "status":"pending"}. A bad config stops start-up with exit code 2 (AC-13).
 */
public final class Main {

    /** One probe: takes its own arguments and writes JSON lines. Returns true on success. */
    interface Probe {
        boolean run(List<String> args, SandboxConfig config, JsonLines out) throws Exception;
    }

    private static final Map<String, Probe> PROBES = new LinkedHashMap<>();

    static {
        PROBES.put("isolate", new IsolateProbe());
        PROBES.put("limits", new LimitsProbe());
        PROBES.put("model", pending("model", "AC-06, AC-07, AC-08"));
        PROBES.put("jline", pending("jline", "AC-10"));
    }

    private Main() {}

    public static void main(String[] argv) {
        System.exit(run(argv, System.out, System.err));
    }

    static int run(String[] argv, PrintStream stdout, PrintStream stderr) {
        JsonLines out = new JsonLines(stdout);
        if (argv.length == 0 || argv[0].equals("-h") || argv[0].equals("--help")) {
            stderr.println("usage: spike <" + String.join("|", PROBES.keySet())
                    + "|all> [--config FILE] [--set PROFILE.KEY=VALUE]... [args...]");
            return argv.length == 0 ? 2 : 0;
        }
        String command = argv[0];
        // Output is always JSON lines; --json is accepted so CI scripts can say so explicitly.
        List<String> args = new ArrayList<>();
        Path configFile = null;
        List<String> overrides = new ArrayList<>();
        for (int i = 1; i < argv.length; i++) {
            switch (argv[i]) {
                case "--json" -> { }
                case "--config", "--set" -> {
                    if (i + 1 >= argv.length) {
                        out.write(JsonLines.obj("probe", "main", "status", "error", "error", argv[i] + " needs a value"));
                        return 2;
                    }
                    if (argv[i].equals("--config")) {
                        configFile = Path.of(argv[++i]);
                    } else {
                        overrides.add(argv[++i]);
                    }
                }
                default -> args.add(argv[i]);
            }
        }

        out.write(environment());
        SandboxConfig config;
        try {
            config = SandboxConfig.load(configFile, overrides);
        } catch (SandboxConfig.ConfigException e) {
            out.write(JsonLines.obj("probe", "config", "status", "error", "error", e.getMessage()));
            return 2;
        }
        if (command.equals("all")) {
            boolean ok = true;
            for (Map.Entry<String, Probe> e : PROBES.entrySet()) {
                ok &= runProbe(e.getKey(), e.getValue(), args, config, out);
            }
            return ok ? 0 : 1;
        }
        Probe probe = PROBES.get(command);
        if (probe == null) {
            out.write(JsonLines.obj("probe", "main", "status", "error", "error", "unknown command: " + command));
            return 2;
        }
        return runProbe(command, probe, args, config, out) ? 0 : 1;
    }

    private static boolean runProbe(String name, Probe probe, List<String> args, SandboxConfig config, JsonLines out) {
        try {
            return probe.run(args, config, out);
        } catch (Exception e) {
            out.write(JsonLines.obj("probe", name, "status", "error", "error", String.valueOf(e)));
            return false;
        }
    }

    private static Probe pending(String name, String criteria) {
        return (args, config, out) -> {
            out.write(JsonLines.obj("probe", name, "status", "pending", "criteria", criteria));
            return true;
        };
    }

    private static Map<String, Object> environment() {
        return JsonLines.obj(
                "probe", "env",
                "os", System.getProperty("os.name"),
                "arch", System.getProperty("os.arch"),
                "java", System.getProperty("java.version"),
                "vm", System.getProperty("java.vm.name"),
                "native", System.getProperty("org.graalvm.nativeimage.imagecode") != null);
    }
}
