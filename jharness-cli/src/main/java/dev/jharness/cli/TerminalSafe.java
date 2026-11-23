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

    /**
     * For live output (spec 022): like {@link #escape} but newlines and tabs stay as they are and a
     * carriage return is dropped, so a reply can't overwrite a line already shown.
     */
    static String text(String s) {
        StringBuilder b = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String r = live(c);
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

    /** The replacement for one character of live output, or null to keep it. */
    private static String live(int c) {
        if (c == '\n' || c == '\t') {
            return null;
        }
        if (c == '\r') {
            return "";
        }
        if (c < 0x20 || c >= 0x7f && c <= 0x9f) {
            return c == 0x1b ? "\\e" : String.format("\\x%02x", c);
        }
        if (c <= 0xffff && bidi((char) c)) {
            return String.format("\\u%04x", c);
        }
        return null;
    }

    /** A writer that applies {@link #text} to everything written, straight through to {@code to}. */
    static final class SafeWriter extends java.io.Writer {
        private final java.io.Writer to;

        SafeWriter(java.io.Writer to) {
            this.to = to;
        }

        @Override
        public void write(char[] buf, int off, int len) throws java.io.IOException {
            to.write(text(new String(buf, off, len)));
        }

        @Override
        public void write(String str, int off, int len) throws java.io.IOException {
            to.write(text(str.substring(off, off + len)));
        }

        @Override
        public void flush() throws java.io.IOException {
            to.flush();
        }

        @Override
        public void close() throws java.io.IOException {
            to.flush();
        }
    }

    /**
     * A UTF-8 stream that applies {@link #text} to everything written (headless output and errors).
     * Multi-byte characters are held until complete, so a C1 or bidi character split across writes is
     * still caught.
     */
    static java.io.PrintStream stream(java.io.PrintStream to) {
        if (to instanceof Safe) {
            return to;
        }
        return new Safe(new SafeBytes(to));
    }

    private static final class Safe extends java.io.PrintStream {
        Safe(SafeBytes out) {
            super(out, true, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static final class SafeBytes extends java.io.OutputStream {
        private final java.io.OutputStream to;
        private final byte[] seq = new byte[4];
        private int have;
        private int need;

        SafeBytes(java.io.OutputStream to) {
            this.to = to;
        }

        @Override
        public void write(int b) throws java.io.IOException {
            b &= 0xff;
            if (need > 0) {
                if ((b & 0xc0) != 0x80) {
                    to.write(seq, 0, have);
                    have = 0;
                    need = 0;
                } else {
                    seq[have++] = (byte) b;
                    if (have == need) {
                        String ch = new String(seq, 0, have, java.nio.charset.StandardCharsets.UTF_8);
                        String r = ch.length() == 1 ? live(ch.charAt(0)) : null;
                        if (r != null) {
                            to.write(r.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                        } else {
                            to.write(seq, 0, have);
                        }
                        have = 0;
                        need = 0;
                    }
                    return;
                }
            }
            if (b < 0x80) {
                String r = live(b);
                if (r != null) {
                    to.write(r.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                } else {
                    to.write(b);
                }
            } else if (b >= 0xc2 && b <= 0xf4) {
                seq[0] = (byte) b;
                have = 1;
                need = b < 0xe0 ? 2 : b < 0xf0 ? 3 : 4;
            } else {
                to.write(b);
            }
        }

        @Override
        public void flush() throws java.io.IOException {
            to.flush();
        }

        @Override
        public void close() throws java.io.IOException {
            flush();
        }
    }
}
