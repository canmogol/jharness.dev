package dev.jharness.cli;

/**
 * Makes logged text safe to print (spec 021 AC-05): session logs hold model replies, file text and
 * tool output, which may carry escape sequences that would rewrite the user's terminal.
 */
final class TerminalSafe {

    private TerminalSafe() {}

    /** Unicode bidi controls, which can reorder what the reader sees. */
    static boolean bidi(char c) {
        return c == '؜' || c == '‎' || c == '‏' || c >= '‪' && c <= '‮' || c >= '⁦' && c <= '⁩';
    }

    /** C0 and C1 controls and DEL. */
    static boolean control(char c) {
        return c < 0x20 || c >= 0x7f && c <= 0x9f;
    }

    /** For text output: newlines and tabs as {@code \n}, {@code \t}, ESC as {@code \e}, other controls as {@code \xNN}, bidi as a backslash, u and four hex digits. */
    static String escape(String s) {
        StringBuilder b = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String r = null;
            if (control(c)) {
                r = switch (c) {
                    case '\n' -> "\\n";
                    case '\r' -> "\\r";
                    case '\t' -> "\\t";
                    case 0x1b -> "\\e";
                    default -> String.format("\\x%02x", (int) c);
                };
            } else if (bidi(c)) {
                r = String.format("\\u%04x", (int) c);
            }
            if (r != null && b == null) {
                b = new StringBuilder(s.length() + 16).append(s, 0, i);
            }
            if (b != null) {
                if (r != null) {
                    b.append(r);
                } else {
                    b.append(c);
                }
            }
        }
        return b == null ? s : b.toString();
    }

    /**
     * For a JSON line: controls and bidi characters become a backslash, u and four hex digits. A raw control can only be
     * inside a JSON string, so the line stays valid JSON with the same value.
     */
    static String escapeJson(String line) {
        StringBuilder b = new StringBuilder(line.length());
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (control(c) || bidi(c)) {
                b.append(String.format("\\u%04x", (int) c));
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}
