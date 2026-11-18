package dev.jharness.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Secret lookup (D-042): the environment first, then {@code ~/.jharness/secrets} with
 * {@code NAME=value} lines. The file is read only if it is a regular file owned by the user with
 * mode 0600. Values are never logged; {@link #toString} shows names only.
 */
public final class Secrets implements Function<String, String> {

    private final Function<String, String> env;
    private final Map<String, String> file;

    private Secrets(Function<String, String> env, Map<String, String> file) {
        this.env = env;
        this.file = Map.copyOf(file);
    }

    public static Path fileFor(Path home) {
        return home.resolve(".jharness").resolve("secrets");
    }

    public static Secrets load(Path home, Function<String, String> env) throws ConfigException {
        Path f = fileFor(home);
        if (!Files.exists(f, LinkOption.NOFOLLOW_LINKS)) {
            return new Secrets(env, Map.of());
        }
        try {
            if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
                throw new ConfigException(f + " must be a regular file, not a link or folder");
            }
            String owner = Files.getOwner(f, LinkOption.NOFOLLOW_LINKS).getName();
            String me = System.getProperty("user.name");
            if (me != null && !owner.equals(me)) {
                throw new ConfigException(f + " is owned by " + owner + ", not " + me);
            }
            String perms = PosixFilePermissions.toString(Files.getPosixFilePermissions(f, LinkOption.NOFOLLOW_LINKS));
            if (!perms.equals("rw-------")) {
                throw new ConfigException(f + " has mode " + perms + "; secrets must be readable by you only: chmod 600 " + f);
            }
            Map<String, String> m = new HashMap<>();
            int n = 0;
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                n++;
                String t = line.strip();
                if (t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                int eq = t.indexOf('=');
                if (eq <= 0 || !t.substring(0, eq).strip().matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    throw new ConfigException(f + " line " + n + ": expected NAME=value");
                }
                m.put(t.substring(0, eq).strip(), t.substring(eq + 1).strip());
            }
            return new Secrets(env, m);
        } catch (IOException e) {
            throw new ConfigException("cannot read " + f + ": " + e.getMessage());
        }
    }

    /** The secret, or null. */
    @Override
    public String apply(String name) {
        String v = env.apply(name);
        if (v != null && !v.isBlank()) {
            return v;
        }
        return file.get(name);
    }

    @Override
    public String toString() {
        return "Secrets" + file.keySet();
    }
}
