package dev.jharness.cli;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jline.terminal.Terminal;

/**
 * Styles TUI output with ANSI colors (spec 009 AC-02): Markdown lines of a streamed reply, step
 * lines, errors and diffs. With color off every method returns its input unchanged.
 */
final class Styler {

    private static final String RESET = "\u001b[0m";
    private static final String BOLD = "\u001b[1m";
    private static final String DIM = "\u001b[2m";
    private static final String RED = "\u001b[31m";
    private static final String GREEN = "\u001b[32m";
    private static final String YELLOW = "\u001b[33m";
    private static final String BLUE = "\u001b[34m";
    private static final String CYAN = "\u001b[36m";

    private static final Pattern FENCE = Pattern.compile("^[ \\t]{0,3}(`{3,}|~{3,}).*$");
    private static final Pattern HEADING = Pattern.compile("^#{1,6}[ \\t].*$");
    private static final Pattern BULLET = Pattern.compile("^([ \\t]*)([-*+]|\\d+[.)])([ \\t].*)$");
    private static final Pattern INLINE = Pattern.compile("`([^`]+)`|\\*\\*([^*]+)\\*\\*");

    private final boolean color;
    private String fence;

    Styler(boolean color) {
        this.color = color;
    }

    /** Color unless {@code NO_COLOR} is set or the terminal is dumb. */
    static Styler forTerminal(Terminal terminal, Map<String, String> env) {
        String noColor = env.get("NO_COLOR");
        boolean dumb = terminal.getType() == null || terminal.getType().startsWith("dumb");
        return new Styler(!dumb && (noColor == null || noColor.isEmpty()));
    }

    boolean color() {
        return color;
    }

    /** Forgets an open code fence, at the start of each reply. */
    void reset() {
        fence = null;
    }

    /** One line of Markdown, without its newline. */
    String line(String line) {
        if (!color) {
            return line;
        }
        Matcher f = FENCE.matcher(line);
        if (fence == null && f.matches()) {
            fence = f.group(1);
            return DIM + line + RESET;
        }
        if (fence != null) {
            String t = line.strip();
            if (t.length() >= fence.length() && t.chars().allMatch(c -> c == fence.charAt(0))) {
                fence = null;
                return DIM + line + RESET;
            }
            return YELLOW + line + RESET;
        }
        if (HEADING.matcher(line).matches()) {
            return BOLD + BLUE + line + RESET;
        }
        Matcher b = BULLET.matcher(line);
        if (b.matches()) {
            return b.group(1) + CYAN + b.group(2) + RESET + inline(b.group(3));
        }
        return inline(line);
    }

    private String inline(String text) {
        Matcher m = INLINE.matcher(text);
        StringBuilder sb = new StringBuilder();
        int at = 0;
        while (m.find()) {
            sb.append(text, at, m.start());
            if (m.group(1) != null) {
                sb.append(YELLOW).append(m.group(1)).append(RESET);
            } else {
                sb.append(BOLD).append(m.group(2)).append(RESET);
            }
            at = m.end();
        }
        return sb.append(text.substring(at)).toString();
    }

    String step(String text) {
        return paint(CYAN, text);
    }

    String error(String text) {
        return paint(RED, text);
    }

    String dim(String text) {
        return paint(DIM, text);
    }

    /** A unified diff, line by line: additions green, removals red, hunk headers cyan. */
    String diff(String diff) {
        if (!color || diff.isEmpty()) {
            return diff;
        }
        StringBuilder sb = new StringBuilder();
        for (String l : diff.split("\n", -1)) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            if (l.startsWith("+++") || l.startsWith("---")) {
                sb.append(BOLD).append(l).append(RESET);
            } else if (l.startsWith("+")) {
                sb.append(GREEN).append(l).append(RESET);
            } else if (l.startsWith("-")) {
                sb.append(RED).append(l).append(RESET);
            } else if (l.startsWith("@@")) {
                sb.append(CYAN).append(l).append(RESET);
            } else {
                sb.append(l);
            }
        }
        return sb.toString();
    }

    private String paint(String code, String text) {
        return color ? code + text + RESET : text;
    }
}
