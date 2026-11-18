package dev.jharness.spike;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader for the spike's config files: objects become ordered maps, arrays lists,
 * numbers {@code Long} or {@code Double}. Duplicate keys are an error.
 */
final class Json {

    static final class ParseException extends Exception {
        ParseException(String message) {
            super(message);
        }
    }

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    static Object parse(String text) throws ParseException {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) {
            throw p.error("trailing characters");
        }
        return v;
    }

    private Object value() throws ParseException {
        if (i >= s.length()) {
            throw error("unexpected end of input");
        }
        char c = s.charAt(i);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) {
                    yield number();
                }
                throw error("unexpected character '" + c + "'");
            }
        };
    }

    private Map<String, Object> object() throws ParseException {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek('}')) {
            i++;
            return m;
        }
        while (true) {
            ws();
            if (!peek('"')) {
                throw error("expected a string key");
            }
            String k = string();
            ws();
            expect(':');
            ws();
            if (m.containsKey(k)) {
                throw error("duplicate key \"" + k + "\"");
            }
            m.put(k, value());
            ws();
            if (peek(',')) {
                i++;
                continue;
            }
            expect('}');
            return m;
        }
    }

    private List<Object> array() throws ParseException {
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (peek(']')) {
            i++;
            return l;
        }
        while (true) {
            ws();
            l.add(value());
            ws();
            if (peek(',')) {
                i++;
                continue;
            }
            expect(']');
            return l;
        }
    }

    private String string() throws ParseException {
        StringBuilder sb = new StringBuilder();
        i++;
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                if (i >= s.length()) {
                    break;
                }
                char e = s.charAt(i++);
                switch (e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) {
                            throw error("bad \\u escape");
                        }
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw error("bad \\u escape");
                        }
                        i += 4;
                    }
                    default -> throw error("bad escape \\" + e);
                }
            } else if (c < 0x20) {
                throw error("control character in string");
            } else {
                sb.append(c);
            }
        }
        throw error("unterminated string");
    }

    private Object number() throws ParseException {
        int start = i;
        if (peek('-')) {
            i++;
        }
        while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        String n = s.substring(start, i);
        try {
            if (n.contains(".") || n.contains("e") || n.contains("E")) {
                return Double.parseDouble(n);
            }
            return Long.parseLong(n);
        } catch (NumberFormatException e) {
            throw error("bad number " + n);
        }
    }

    private Object literal(String word, Object v) throws ParseException {
        if (!s.startsWith(word, i)) {
            throw error("unexpected token");
        }
        i += word.length();
        return v;
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    private boolean peek(char c) {
        return i < s.length() && s.charAt(i) == c;
    }

    private void expect(char c) throws ParseException {
        if (!peek(c)) {
            throw error("expected '" + c + "'");
        }
        i++;
    }

    private ParseException error(String message) {
        return new ParseException("invalid JSON at offset " + i + ": " + message);
    }
}
