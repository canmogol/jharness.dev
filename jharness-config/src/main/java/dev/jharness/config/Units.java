package dev.jharness.config;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Sizes ({@code 256KB}, {@code 512MB}) and durations ({@code 500ms}, {@code 30s}, {@code 15m}, {@code 1h}). */
public final class Units {

    private static final Pattern SIZE = Pattern.compile("([0-9]{1,12})(B|KB|MB|GB)");
    private static final Pattern DURATION = Pattern.compile("([0-9]{1,12})(ms|s|m|h)");

    private Units() {}

    /** Canonical size text, or null if it is not a size above zero. */
    static String size(String v) {
        Matcher m = SIZE.matcher(v.trim().toUpperCase());
        if (!m.matches() || Long.parseLong(m.group(1)) == 0) {
            return null;
        }
        return Long.parseLong(m.group(1)) + m.group(2);
    }

    /** Canonical duration text, or null if it is not a duration above zero. */
    static String duration(String v) {
        Matcher m = DURATION.matcher(v.trim());
        if (!m.matches() || Long.parseLong(m.group(1)) == 0) {
            return null;
        }
        return Long.parseLong(m.group(1)) + m.group(2);
    }

    public static long bytes(String size) {
        Matcher m = SIZE.matcher(size);
        if (!m.matches()) {
            throw new IllegalArgumentException(size);
        }
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "B" -> n;
            case "KB" -> n << 10;
            case "MB" -> n << 20;
            default -> n << 30;
        };
    }

    public static Duration parseDuration(String d) {
        Matcher m = DURATION.matcher(d);
        if (!m.matches()) {
            throw new IllegalArgumentException(d);
        }
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "ms" -> Duration.ofMillis(n);
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            default -> Duration.ofHours(n);
        };
    }
}
