package dev.jharness.spike;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes JSON objects, one per line. Values: strings, numbers, booleans, null, maps, lists. */
final class JsonLines {

    private final PrintStream out;

    JsonLines(PrintStream out) {
        this.out = out;
    }

    /** Ordered object from key/value pairs: {@code obj("probe", "isolate", "ms", 12)}. */
    static Map<String, Object> obj(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("odd number of key/value arguments");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    void write(Map<String, ?> object) {
        StringBuilder sb = new StringBuilder();
        value(sb, object);
        out.println(sb);
        out.flush();
    }

    private static void value(StringBuilder sb, Object v) {
        if (v == null || v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Double d && (d.isNaN() || d.isInfinite())) {
            sb.append("null");
        } else if (v instanceof Number) {
            sb.append(v);
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                value(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof List<?> l) {
            sb.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                value(sb, l.get(i));
            }
            sb.append(']');
        } else {
            quote(sb, v.toString());
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
