package dev.jharness.net;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import dev.jharness.config.ConfigException;

/**
 * A network rules file (D-025): one rule per line, {@code #} comments, keywords {@code default},
 * {@code allow} and {@code deny}. A missing file is empty; a bad line stops start-up (AC-01).
 */
public record RulesFile(Path path, Optional<Rule.Action> defaultAction, List<Rule> rules) {

    public static RulesFile empty(Path path) {
        return new RulesFile(path, Optional.empty(), List.of());
    }

    public static RulesFile load(Path path) throws ConfigException {
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return empty(path);
        } catch (IOException e) {
            throw new ConfigException("cannot read " + path + ": " + e.getMessage());
        }
        return parse(text, path);
    }

    public static RulesFile parse(String text, Path path) throws ConfigException {
        Optional<Rule.Action> def = Optional.empty();
        List<Rule> rules = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String where = path + ":" + (i + 1);
            String line = lines[i];
            int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash);
            }
            line = line.strip();
            if (line.isEmpty()) {
                continue;
            }
            String[] w = line.split("\\s+");
            String keyword = w[0].toLowerCase(Locale.ROOT);
            if (w.length != 2) {
                throw new ConfigException(where + ": expected \"default ask|allow|deny\", \"allow X\" or \"deny X\", got \"" + line + "\"");
            }
            switch (keyword) {
                case "default" -> {
                    Rule.Action a = Rule.Action.parse(w[1].toLowerCase(Locale.ROOT));
                    if (a == null) {
                        throw new ConfigException(where + ": default must be ask, allow or deny, got \"" + w[1] + "\"");
                    }
                    if (def.isPresent()) {
                        throw new ConfigException(where + ": a second default line");
                    }
                    def = Optional.of(a);
                }
                case "allow", "deny" -> {
                    try {
                        rules.add(rule(Rule.Action.parse(keyword), w[1], where));
                    } catch (IllegalArgumentException e) {
                        throw new ConfigException(where + ": " + e.getMessage());
                    }
                }
                default -> throw new ConfigException(where + ": unknown keyword \"" + w[0] + "\" (known: default, allow, deny)");
            }
        }
        return new RulesFile(path, def, List.copyOf(rules));
    }

    /** Parses {@code host}, {@code *.suffix}, an address or CIDR, each with an optional {@code :port}. */
    public static Rule rule(Rule.Action action, String text, String source) {
        String t = text.toLowerCase(Locale.ROOT);
        String target;
        int port = 0;
        if (t.startsWith("[")) {
            int close = t.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException("missing ] in " + text);
            }
            target = t.substring(1, close);
            String rest = t.substring(close + 1);
            if (!rest.isEmpty()) {
                if (!rest.startsWith(":")) {
                    throw new IllegalArgumentException("expected :port after ] in " + text);
                }
                port = port(rest.substring(1), text);
            }
            return new Rule(action, cidr(target), port, source);
        }
        long colons = t.chars().filter(c -> c == ':').count();
        if (colons >= 2) {
            return new Rule(action, cidr(t), 0, source);
        }
        if (colons == 1) {
            int c = t.indexOf(':');
            target = t.substring(0, c);
            port = port(t.substring(c + 1), text);
        } else {
            target = t;
        }
        if (target.matches("[0-9.]+(/[0-9]+)?")) {
            return new Rule(action, cidr(target), port, source);
        }
        if (target.startsWith("*.")) {
            String suffix = target.substring(2);
            checkHost(suffix, text);
            return new Rule(action, new Rule.Target.Suffix(suffix), port, source);
        }
        checkHost(target, text);
        return new Rule(action, new Rule.Target.Host(target), port, source);
    }

    private static Rule.Target cidr(String text) {
        try {
            return new Rule.Target.Cidr(Range.parse(text), text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("bad address or range \"" + text + "\": " + e.getMessage());
        }
    }

    private static int port(String p, String text) {
        if (!p.matches("[0-9]{1,5}") || Integer.parseInt(p) < 1 || Integer.parseInt(p) > 65535) {
            throw new IllegalArgumentException("bad port in " + text);
        }
        return Integer.parseInt(p);
    }

    private static void checkHost(String h, String text) {
        if (!h.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*")) {
            throw new IllegalArgumentException("bad host \"" + text + "\"");
        }
    }
}
