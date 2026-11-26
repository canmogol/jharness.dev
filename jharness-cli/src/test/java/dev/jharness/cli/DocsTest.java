package dev.jharness.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import dev.jharness.config.Settings;

/**
 * Spec 065: the user guide, the extension guide and the api reference point at files that exist, quote the examples
 * as they are, and name every settings key.
 */
class DocsTest {

    static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    static final List<String> DOCS = List.of("docs/user-guide.md", "docs/extensions.md", "docs/api.md", "README.md");

    static String read(String rel) throws Exception {
        return Files.readString(ROOT.resolve(rel), StandardCharsets.UTF_8);
    }

    /** GitHub's heading anchors: lower case, punctuation dropped, spaces to dashes. */
    static Set<String> anchors(String md) {
        Set<String> a = new HashSet<>();
        boolean fence = false;
        for (String l : md.split("\n")) {
            if (l.startsWith("```")) {
                fence = !fence;
            } else if (!fence && l.startsWith("#")) {
                String h = l.replaceFirst("^#+\\s*", "").toLowerCase(Locale.ROOT);
                a.add(h.replaceAll("[^a-z0-9 _-]", "").replace(' ', '-'));
            }
        }
        return a;
    }

    @Test
    void linksPointAtFilesAndHeadingsThatExist() throws Exception {
        Pattern link = Pattern.compile("\\]\\(([^)\\s]+)\\)");
        for (String doc : DOCS) {
            String md = read(doc).replaceAll("(?s)```.*?```", "").replaceAll("`[^`\n]*`", "");
            Path dir = ROOT.resolve(doc).getParent();
            Matcher m = link.matcher(md);
            while (m.find()) {
                String target = m.group(1);
                if (target.startsWith("http://") || target.startsWith("https://")) {
                    continue;
                }
                int hash = target.indexOf('#');
                String file = hash < 0 ? target : target.substring(0, hash);
                String anchor = hash < 0 ? null : target.substring(hash + 1);
                Path p = file.isEmpty() ? ROOT.resolve(doc) : dir.resolve(file).normalize();
                assertTrue(Files.exists(p), doc + " links to " + target + ", which doesn't exist");
                if (anchor != null && p.toString().endsWith(".md")) {
                    assertTrue(anchors(Files.readString(p)).contains(anchor), doc + " links to " + target + ", a heading that doesn't exist");
                }
            }
        }
    }

    @Test
    void examplePathsExist() throws Exception {
        Pattern path = Pattern.compile("examples/[A-Za-z0-9_./-]*[A-Za-z0-9_/-]");
        for (String doc : List.of("docs/user-guide.md", "docs/extensions.md")) {
            Matcher m = path.matcher(read(doc));
            while (m.find()) {
                assertTrue(Files.exists(ROOT.resolve(m.group())), doc + " names " + m.group() + ", which doesn't exist");
            }
        }
    }

    @Test
    void quotedExamplesAreTheFilesAsTheyAre() throws Exception {
        Pattern quoted = Pattern.compile("<!-- file: (\\S+) -->\n```\\w*\n(.*?)\n```", Pattern.DOTALL);
        int n = 0;
        for (String doc : List.of("docs/user-guide.md", "docs/extensions.md")) {
            Matcher m = quoted.matcher(read(doc));
            while (m.find()) {
                assertEquals(read(m.group(1)).stripTrailing(), m.group(2), doc + " quotes " + m.group(1) + " out of date");
                n++;
            }
        }
        assertTrue(n >= 7, "the extension guide quotes its examples: " + n);
    }

    @Test
    void theSettingsReferenceNamesEveryKey() throws Exception {
        String guide = read("docs/user-guide.md");
        String ref = guide.substring(guide.indexOf("## Settings reference"));
        for (String key : Settings.TOP) {
            assertTrue(ref.contains("| `" + key + "` |"), "the settings reference has no row for " + key);
        }
    }

    @Test
    void theReadmeLinksTheGuides() throws Exception {
        String readme = read("README.md");
        for (String doc : List.of("docs/user-guide.md", "docs/extensions.md", "docs/api.md")) {
            assertTrue(readme.contains("](" + doc + ")"), "README.md doesn't link " + doc);
        }
    }
}
