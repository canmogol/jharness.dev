package dev.jharness.process;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The OS sandbox every child process runs in (D-048, spec 012): Seatbelt on macOS, bubblewrap on
 * Linux. Children may write only to the workspace (not its {@code .jharness/}) and a private temp
 * folder, can't read credential folders, and have no network. When the sandbox is missing or
 * doesn't work, it is unavailable and {@link ProcessRunner} runs nothing. Build tools (spec 013) get
 * {@link Grants}: their cache folders, and a network path to the egress proxy's port only (D-049).
 */
public final class ChildSandbox {

    static final Path SANDBOX_EXEC = Path.of("/usr/bin/sandbox-exec");
    static final Path BWRAP = Path.of("/usr/bin/bwrap");
    static final Path TRUE = Path.of("/usr/bin/true");
    static final Path TOUCH = Path.of("/usr/bin/touch");

    /**
     * @param workspace the project folder; writable except {@code .jharness/}
     * @param tmp a private temp folder; writable, passed as {@code TMPDIR}
     * @param hidden folders and files children can't read
     */
    public record Policy(Path workspace, Path tmp, List<Path> hidden) {

        public Policy {
            hidden = List.copyOf(hidden);
        }

        /** The D-048 hidden list under {@code home}. */
        public static Policy forSession(Path workspace, Path tmp, Path home) {
            return new Policy(workspace, tmp, List.of(home.resolve(".jharness"), home.resolve(".ssh"), home.resolve(".aws"),
                    home.resolve(".gnupg"), home.resolve(".config").resolve("gcloud"), home.resolve(".netrc")));
        }
    }

    /**
     * How a sandboxed child reaches the egress proxy: {@code 127.0.0.1:port} inside the sandbox. On
     * Linux the {@link ProxyBridge} forwards that port to {@code socket}, which the proxy serves.
     */
    public record ProxyRoute(int port, Path socket, String user, String password) {

        @Override
        public String toString() {
            return "ProxyRoute[port=" + port + ", socket=" + socket + "]";
        }
    }

    /**
     * What one run may do beyond the base policy.
     *
     * @param writable extra folders the child may write (they must exist)
     * @param network whether the child may connect to the proxy port
     * @param loopback whether the child may also listen on and connect to any loopback port; on
     *     macOS only Gradle gets this, for its daemon (D-050, an Article V exception). Linux ignores
     *     it: loopback there is private to the sandbox anyway
     */
    public record Grants(List<Path> writable, boolean network, boolean loopback) {

        public static final Grants NONE = new Grants(List.of(), false, false);

        public Grants {
            writable = List.copyOf(writable);
            if (loopback && !network) {
                throw new IllegalArgumentException("loopback needs network");
            }
        }

        public Grants(List<Path> writable, boolean network) {
            this(writable, network, false);
        }
    }

    public enum Mechanism { SEATBELT, BUBBLEWRAP, NONE;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final Policy policy;
    private final Mechanism mechanism;
    private final String reason;
    private final ProxyRoute route;
    private final List<String> bridge;

    private ChildSandbox(Policy policy, Mechanism mechanism, String reason) {
        this(policy, mechanism, reason, null, List.of());
    }

    private ChildSandbox(Policy policy, Mechanism mechanism, String reason, ProxyRoute route, List<String> bridge) {
        this.policy = policy;
        this.mechanism = mechanism;
        this.reason = reason;
        this.route = route;
        this.bridge = List.copyOf(bridge);
    }

    /**
     * The same sandbox, with a network path to the proxy for runs whose {@link Grants} ask for it.
     *
     * @param bridgeCommand how to start {@link ProxyBridge} inside the sandbox (Linux); see {@link #selfBridgeCommand}
     */
    public ChildSandbox withProxy(ProxyRoute route, List<String> bridgeCommand) {
        return new ChildSandbox(policy, mechanism, reason, route, bridgeCommand);
    }

    /** The proxy route, or null when runs can't have network. */
    public ProxyRoute route() {
        return route;
    }

    /**
     * How this program starts itself as the bridge: the native binary with {@link ProxyBridge#FLAG},
     * or on the JVM the same {@code java} and class path.
     */
    public static List<String> selfBridgeCommand() {
        if ("runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"))) {
            String exe = ProcessHandle.current().info().command()
                    .orElseThrow(() -> new IllegalStateException("cannot find this program's path"));
            return List.of(exe, ProxyBridge.FLAG);
        }
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-XX:-UsePerfData",
                "-XX:TieredStopAtLevel=1", "-cp", System.getProperty("java.class.path"), ProxyBridge.class.getName());
    }

    /**
     * Picks the mechanism for this OS and probes it once: {@code /usr/bin/true} must run inside it, and
     * {@code /usr/bin/touch} on a path outside the workspace must fail, so a sandbox that allows
     * everything is caught too.
     */
    public static ChildSandbox detect(Policy policy) {
        return detect(policy, System.getProperty("os.name", ""));
    }

    static ChildSandbox detect(Policy policy, String osName) {
        Policy real = real(policy);
        String os = osName.toLowerCase(Locale.ROOT);
        Mechanism m;
        if (os.contains("mac")) {
            m = Mechanism.SEATBELT;
            if (!Files.isExecutable(SANDBOX_EXEC)) {
                return unavailable(real, m, SANDBOX_EXEC + " is missing");
            }
        } else if (os.contains("linux")) {
            m = Mechanism.BUBBLEWRAP;
            if (!Files.isExecutable(BWRAP)) {
                return unavailable(real, m, "bubblewrap is not installed; install it (for example: sudo apt install bubblewrap)");
            }
            try {
                Files.createDirectories(real.workspace().resolve(".jharness"));
            } catch (IOException e) {
                return unavailable(real, m, "cannot create " + real.workspace().resolve(".jharness") + ": " + e.getMessage());
            }
        } else {
            return unavailable(real, Mechanism.NONE, "no OS sandbox for " + osName + "; JHarness runs no processes here");
        }
        ChildSandbox s = new ChildSandbox(real, m, null);
        ProcessRunner.Probe p = ProcessRunner.probe(s.wrap(List.of(TRUE.toString()), real.workspace()), real.workspace(), Duration.ofSeconds(15));
        if (p.exitCode() != 0) {
            String why = m.wire() + " did not start a process (exit " + p.exitCode() + "): " + clip(p.output());
            if (m == Mechanism.BUBBLEWRAP) {
                why += "; bubblewrap needs unprivileged user namespaces. On Ubuntu 24.04 allow them for bwrap only with an AppArmor"
                        + " profile (see the JHarness README), or for every program with: sudo sysctl kernel.apparmor_restrict_unprivileged_userns=0";
            }
            return unavailable(real, m, why);
        }
        Path outside = real.tmp().getParent().resolve("jharness-probe-" + Long.toHexString(System.nanoTime()));
        ProcessRunner.Probe w = ProcessRunner.probe(s.wrap(List.of(TOUCH.toString(), outside.toString()), real.workspace()),
                real.workspace(), Duration.ofSeconds(15));
        if (w.exitCode() == 0 || Files.exists(outside)) {
            try {
                Files.deleteIfExists(outside);
            } catch (IOException e) {
                // reported below
            }
            return unavailable(real, m, m.wire() + " did not stop a write outside the workspace");
        }
        return s;
    }

    private static ChildSandbox unavailable(Policy p, Mechanism m, String reason) {
        return new ChildSandbox(p, m, reason);
    }

    private static Policy real(Policy p) {
        try {
            return new Policy(p.workspace().toRealPath(), p.tmp().toRealPath(),
                    p.hidden().stream().map(ChildSandbox::realOrAbsolute).toList());
        } catch (IOException e) {
            throw new UncheckedIOException("sandbox folders must exist: " + e.getMessage(), e);
        }
    }

    private static Path realOrAbsolute(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    private static String clip(String s) {
        String t = s.strip();
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }

    public boolean available() {
        return reason == null;
    }

    public Mechanism mechanism() {
        return mechanism;
    }

    /** Why the sandbox is unavailable and how to fix it; null when available. */
    public String reason() {
        return reason;
    }

    public Policy policy() {
        return policy;
    }

    /** Extra environment for every child: {@code TMPDIR} is the private temp folder. */
    Map<String, String> env() {
        return Map.of("TMPDIR", policy.tmp().toString());
    }

    /** The argv that runs {@code argv} inside the sandbox, with {@code cwd} as its working folder. */
    List<String> wrap(List<String> argv, Path cwd) {
        return wrap(argv, cwd, Grants.NONE);
    }

    List<String> wrap(List<String> argv, Path cwd, Grants g) {
        if (g.network() && route == null) {
            throw new OperationException("this run needs the network, but the session has no egress proxy");
        }
        Grants real = new Grants(g.writable().stream().map(ChildSandbox::realOrAbsolute).toList(), g.network(), g.loopback());
        List<String> out = new ArrayList<>();
        switch (mechanism) {
            case SEATBELT -> {
                out.add(SANDBOX_EXEC.toString());
                out.add("-p");
                out.add(seatbeltProfile(policy, real, route));
                out.add("--");
            }
            case BUBBLEWRAP -> {
                out.addAll(bubblewrapArgs(policy, cwd, real, route));
                out.add("--");
                if (real.network()) {
                    if (bridge.isEmpty()) {
                        throw new OperationException("this run needs the network, but there is no proxy bridge");
                    }
                    out.addAll(bridge);
                    out.addAll(List.of(route.socket().toString(), Integer.toString(route.port()), "--"));
                }
            }
            case NONE -> throw new OperationException(reason);
        }
        out.addAll(argv);
        return out;
    }

    static String seatbeltProfile(Policy p) {
        return seatbeltProfile(p, Grants.NONE, null);
    }

    static String seatbeltProfile(Policy p, Grants g, ProxyRoute route) {
        StringBuilder sb = new StringBuilder("""
                (version 1)
                (deny default)
                (allow process-fork)
                (allow process-exec)
                (allow signal (target same-sandbox))
                (allow sysctl-read)
                (allow mach-lookup)
                (allow ipc-posix-shm-read-data)
                (allow file-read*)
                """);
        if (!p.hidden().isEmpty()) {
            sb.append("(deny file-read*");
            for (Path h : p.hidden()) {
                sb.append(" (subpath ").append(quote(h)).append(')');
            }
            sb.append(")\n");
        }
        sb.append("(allow file-write* (subpath ").append(quote(p.workspace())).append(") (subpath ").append(quote(p.tmp())).append(')');
        for (Path w : g.writable()) {
            sb.append(" (subpath ").append(quote(w)).append(')');
        }
        sb.append(" (literal \"/dev/null\") (literal \"/dev/tty\") (regex #\"^/dev/fd/\"))\n");
        sb.append("(deny file-write* (subpath ").append(quote(p.workspace().resolve(".jharness"))).append("))\n");
        sb.append("(allow file-ioctl (literal \"/dev/null\"))\n");
        if (g.network()) {
            // The only network rule: the proxy's port on loopback (D-049). Everything else stays denied.
            sb.append("(allow network-outbound (remote ip \"localhost:").append(route.port()).append("\"))\n");
        }
        if (g.loopback()) {
            // D-050, Article V exception for Gradle: its client and daemon talk over loopback TCP and it
            // uses loopback UDP for lock contention. Seatbelt can't limit this to Gradle's own sockets,
            // so the build can reach any local service; outside hosts stay blocked.
            sb.append("(allow network-bind (local ip \"localhost:*\"))\n");
            sb.append("(allow network-inbound (local ip \"localhost:*\"))\n");
            sb.append("(allow network-outbound (remote ip \"localhost:*\"))\n");
        }
        return sb.toString();
    }

    static String quote(Path p) {
        return "\"" + p.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static List<String> bubblewrapArgs(Policy p, Path cwd) {
        return bubblewrapArgs(p, cwd, Grants.NONE, null);
    }

    /** {@code --unshare-all} keeps a private network namespace even with network: only the bridge's socket leads out. */
    static List<String> bubblewrapArgs(Policy p, Path cwd, Grants g, ProxyRoute route) {
        String ws = p.workspace().toString();
        String jh = p.workspace().resolve(".jharness").toString();
        List<String> a = new ArrayList<>(List.of(BWRAP.toString(), "--die-with-parent", "--new-session", "--unshare-all",
                "--ro-bind", "/", "/", "--dev", "/dev", "--proc", "/proc", "--bind", ws, ws, "--ro-bind", jh, jh,
                "--bind", p.tmp().toString(), p.tmp().toString()));
        for (Path w : g.writable()) {
            a.addAll(List.of("--bind", w.toString(), w.toString()));
        }
        if (g.network()) {
            a.addAll(List.of("--bind", route.socket().toString(), route.socket().toString()));
        }
        for (Path h : p.hidden()) {
            if (Files.isDirectory(h)) {
                a.addAll(List.of("--tmpfs", h.toString()));
            } else if (Files.exists(h)) {
                a.addAll(List.of("--ro-bind", "/dev/null", h.toString()));
            }
        }
        a.addAll(List.of("--chdir", cwd.toString()));
        return a;
    }
}
