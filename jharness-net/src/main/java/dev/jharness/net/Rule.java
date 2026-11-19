package dev.jharness.net;

import java.net.InetAddress;
import java.util.Locale;

/**
 * One {@code allow} or {@code deny} line of a rules file (D-012, D-025).
 *
 * @param port 0 for any port
 * @param source where the rule came from, e.g. {@code ~/.jharness/network.rules:3}
 */
public record Rule(Action action, Target target, int port, String source) {

    public enum Action {
        ALLOW, DENY, ASK;

        static Action parse(String word) {
            return switch (word) {
                case "allow" -> ALLOW;
                case "deny" -> DENY;
                case "ask" -> ASK;
                default -> null;
            };
        }

        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** What a rule names: an exact host, a {@code *.suffix}, or an address range. */
    public sealed interface Target {
        /** True when the rule names this connection by exact host or address, not by a wildcard. */
        boolean explicit();

        record Host(String name) implements Target {
            public boolean explicit() {
                return true;
            }

            @Override
            public String toString() {
                return name;
            }
        }

        record Suffix(String suffix) implements Target {
            public boolean explicit() {
                return false;
            }

            @Override
            public String toString() {
                return "*." + suffix;
            }
        }

        record Cidr(Range range, String text) implements Target {
            public boolean explicit() {
                return true;
            }

            @Override
            public String toString() {
                return text;
            }
        }
    }

    public boolean matches(String host, int port, InetAddress ip) {
        if (this.port != 0 && this.port != port) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        return switch (target) {
            case Target.Host t -> t.name().equals(h);
            case Target.Suffix t -> h.endsWith("." + t.suffix());
            case Target.Cidr t -> t.range().contains(ip);
        };
    }

    /** The line as written in a rules file. */
    public String line() {
        String t = target.toString();
        if (port == 0) {
            return action.word() + " " + t;
        }
        boolean v6 = target instanceof Target.Cidr && t.contains(":");
        return action.word() + " " + (v6 ? "[" + t + "]" : t) + ":" + port;
    }
}
