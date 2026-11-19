package dev.jharness.net;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Edits a rules file for {@code /network allow|deny|remove} (spec 011 AC-09), keeping other lines and comments. */
public final class RulesEditor {

    private RulesEditor() {}

    /** Appends {@code allow X} or {@code deny X}; X is checked first. @return the line written */
    public static String add(Path file, Rule.Action action, String target) throws IOException {
        Rule r = RulesFile.rule(action, target, file.toString());
        String line = r.line();
        Files.createDirectories(file.getParent());
        String text = read(file);
        String prefix = !text.isEmpty() && !text.endsWith("\n") ? "\n" : "";
        Files.writeString(file, text + prefix + line + "\n", StandardCharsets.UTF_8);
        return line;
    }

    /** Removes every {@code allow} or {@code deny} line naming X (same target and port). @return lines removed */
    public static int remove(Path file, String target) throws IOException {
        Rule wanted = RulesFile.rule(Rule.Action.ALLOW, target, file.toString());
        String text = read(file);
        if (text.isEmpty()) {
            return 0;
        }
        List<String> kept = new ArrayList<>();
        int removed = 0;
        for (String line : text.split("\n", -1)) {
            String body = line.contains("#") ? line.substring(0, line.indexOf('#')) : line;
            String[] w = body.strip().split("\\s+");
            if (w.length == 2 && (w[0].equalsIgnoreCase("allow") || w[0].equalsIgnoreCase("deny"))) {
                try {
                    Rule r = RulesFile.rule(Rule.Action.ALLOW, w[1], file.toString());
                    if (r.target().toString().equals(wanted.target().toString()) && r.port() == wanted.port()) {
                        removed++;
                        continue;
                    }
                } catch (IllegalArgumentException e) {
                    // keep lines we can't read; loading reports them
                }
            }
            kept.add(line);
        }
        if (removed > 0) {
            Files.writeString(file, String.join("\n", kept), StandardCharsets.UTF_8);
        }
        return removed;
    }

    private static String read(Path file) throws IOException {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return "";
        }
    }
}
