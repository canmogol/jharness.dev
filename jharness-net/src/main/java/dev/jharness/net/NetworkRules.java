package dev.jharness.net;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Decides one connection (D-012, D-025, AC-02): built-in denies unless an {@code allow} names the
 * address explicitly; the project file first (every line when trusted, only {@code deny} lines when
 * not); then the general file with the automatic allows; within a file {@code deny} beats
 * {@code allow}; no match falls to the default (the project's when trusted), else {@code ask}.
 */
public final class NetworkRules {

    /** Loopback, private, link-local (and cloud metadata), unique-local, CGNAT and unspecified ranges. */
    public static final List<String> BUILT_IN_DENY = List.of("127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
            "169.254.0.0/16", "100.64.0.0/10", "0.0.0.0/8", "::1/128", "::/128", "fc00::/7", "fe80::/10");

    private static final List<Range> BUILT_IN = BUILT_IN_DENY.stream().map(Range::parse).toList();

    public record Decision(Rule.Action action, String reason) {}

    private final RulesFile general;
    private final RulesFile project;
    private final boolean trusted;
    private final List<Rule> automatic;

    public NetworkRules(RulesFile general, RulesFile project, boolean trusted, List<Rule> automatic) {
        this.general = general;
        this.project = project;
        this.trusted = trusted;
        this.automatic = List.copyOf(automatic);
    }

    public RulesFile general() {
        return general;
    }

    public RulesFile project() {
        return project;
    }

    public boolean trusted() {
        return trusted;
    }

    public List<Rule> automatic() {
        return automatic;
    }

    /** The project rules that count: all of them when trusted, else only the denies. */
    private List<Rule> projectRules() {
        return trusted ? project.rules() : project.rules().stream().filter(r -> r.action() == Rule.Action.DENY).toList();
    }

    private List<Rule> generalRules() {
        List<Rule> l = new ArrayList<>(automatic);
        l.addAll(general.rules());
        return l;
    }

    public Decision decide(String host, int port, InetAddress ip) {
        List<Rule> projectRules = projectRules();
        List<Rule> generalRules = generalRules();
        Optional<Rule> projectDeny = first(projectRules, Rule.Action.DENY, host, port, ip);
        if (projectDeny.isPresent()) {
            return deny(projectDeny.get());
        }
        for (Range r : BUILT_IN) {
            if (r.contains(ip)) {
                boolean named = projectRules.stream().anyMatch(x -> x.action() == Rule.Action.ALLOW && x.target().explicit()
                        && x.matches(host, port, ip))
                        || generalRules.stream().anyMatch(x -> x.action() == Rule.Action.ALLOW && x.target().explicit()
                                && x.matches(host, port, ip) && first(generalRules, Rule.Action.DENY, host, port, ip).isEmpty());
                if (!named) {
                    return new Decision(Rule.Action.DENY, "built-in deny of internal addresses (" + ip.getHostAddress() + ")");
                }
                break;
            }
        }
        Optional<Rule> projectAllow = first(projectRules, Rule.Action.ALLOW, host, port, ip);
        if (projectAllow.isPresent()) {
            return allow(projectAllow.get());
        }
        Optional<Rule> generalDeny = first(generalRules, Rule.Action.DENY, host, port, ip);
        if (generalDeny.isPresent()) {
            return deny(generalDeny.get());
        }
        Optional<Rule> generalAllow = first(generalRules, Rule.Action.ALLOW, host, port, ip);
        if (generalAllow.isPresent()) {
            return allow(generalAllow.get());
        }
        if (trusted && project.defaultAction().isPresent()) {
            return new Decision(project.defaultAction().get(), "default " + project.defaultAction().get().word() + " (" + project.path() + ")");
        }
        if (general.defaultAction().isPresent()) {
            return new Decision(general.defaultAction().get(), "default " + general.defaultAction().get().word() + " (" + general.path() + ")");
        }
        return new Decision(Rule.Action.ASK, "no rule matched");
    }

    private static Optional<Rule> first(List<Rule> rules, Rule.Action action, String host, int port, InetAddress ip) {
        return rules.stream().filter(r -> r.action() == action && r.matches(host, port, ip)).findFirst();
    }

    private static Decision deny(Rule r) {
        return new Decision(Rule.Action.DENY, r.line() + " (" + r.source() + ")");
    }

    private static Decision allow(Rule r) {
        return new Decision(Rule.Action.ALLOW, r.line() + " (" + r.source() + ")");
    }
}
