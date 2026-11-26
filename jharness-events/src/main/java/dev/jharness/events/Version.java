package dev.jharness.events;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** JHarness's version, from the pom (spec 062): Maven fills {@code version.properties} in at build time. */
public final class Version {

    /** Such as {@code 0.1.0} or {@code 0.2.0-SNAPSHOT}; {@code unknown} if the resource is missing. */
    public static final String CURRENT = read();

    private Version() {
    }

    private static String read() {
        try (InputStream in = Version.class.getResourceAsStream("version.properties")) {
            if (in == null) {
                return "unknown";
            }
            Properties p = new Properties();
            p.load(in);
            String v = p.getProperty("version", "").strip();
            return v.isEmpty() || v.startsWith("${") ? "unknown" : v;
        } catch (IOException e) {
            return "unknown";
        }
    }
}
