package dev.jharness.sandbox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spec 065: builds {@code docs/api.md} from {@code api.d.ts}, the file the model gets, so the reference can't drift from
 * it. {@link ApiReferenceTest} compares the two; {@code scripts/api-reference.sh} writes the file.
 */
final class ApiReference {

    static final String SOURCE = "jharness-sandbox/src/main/resources/dev/jharness/sandbox/api.d.ts";

    /** A top-level type or interface: its name and its source text, comment included. */
    record Type(String name, String text) {}

    /** A member of {@code declare const api}: name, parameters, result type and comment as prose. */
    record Method(String name, String params, String result, String doc) {}

    record Parsed(String overview, List<Type> types, List<Method> methods) {}

    private static final Pattern TYPE_START = Pattern.compile("^(type|interface)\\s+(\\w+)");
    private static final Pattern MEMBER = Pattern.compile("^\\s+(\\w+)\\((.*)\\):\\s*(.+);\\s*$");
    private static final Pattern NAME = Pattern.compile("\\b[A-Z]\\w*\\b");

    private ApiReference() {}

    static Parsed parse(String dts) {
        String[] lines = dts.split("\n", -1);
        int i = 0;
        StringBuilder comment = new StringBuilder();
        String overview = null;
        List<Type> types = new ArrayList<>();
        List<Method> methods = new ArrayList<>();
        while (i < lines.length) {
            String line = lines[i];
            if (line.strip().startsWith("/**")) {
                int end = i;
                while (!lines[end].contains("*/")) {
                    end++;
                }
                comment.setLength(0);
                for (int k = i; k <= end; k++) {
                    comment.append(lines[k]).append('\n');
                }
                i = end + 1;
                if (overview == null) {
                    overview = prose(comment.toString());
                    comment.setLength(0);
                }
                continue;
            }
            Matcher t = TYPE_START.matcher(line);
            if (t.find()) {
                int end = t.group(1).equals("interface") ? closing(lines, i) : typeEnd(lines, i);
                StringBuilder text = new StringBuilder(comment);
                for (int k = i; k <= end; k++) {
                    text.append(lines[k]).append('\n');
                }
                types.add(new Type(t.group(2), text.toString().stripTrailing()));
                comment.setLength(0);
                i = end + 1;
                continue;
            }
            if (line.startsWith("declare const api")) {
                i++;
                String doc = "";
                while (!lines[i].startsWith("};")) {
                    String l = lines[i];
                    if (l.strip().startsWith("/**")) {
                        StringBuilder c = new StringBuilder();
                        while (true) {
                            c.append(lines[i]).append('\n');
                            if (lines[i].contains("*/")) {
                                break;
                            }
                            i++;
                        }
                        doc = prose(c.toString());
                    } else {
                        Matcher m = MEMBER.matcher(l);
                        if (m.matches()) {
                            methods.add(new Method(m.group(1), m.group(2).strip(), m.group(3).strip(), doc));
                            doc = "";
                        } else if (!l.isBlank()) {
                            throw new IllegalArgumentException("api.d.ts: can't read the api member line \"" + l + "\"");
                        }
                    }
                    i++;
                }
                i++;
                continue;
            }
            if (!line.isBlank()) {
                throw new IllegalArgumentException("api.d.ts: can't read the line \"" + line + "\"");
            }
            i++;
        }
        return new Parsed(overview, types, methods);
    }

    /** The line of the {@code }} that closes the interface starting at {@code start}. */
    private static int closing(String[] lines, int start) {
        for (int k = start; k < lines.length; k++) {
            if (lines[k].equals("}")) {
                return k;
            }
        }
        throw new IllegalArgumentException("api.d.ts: interface at line " + (start + 1) + " never closes");
    }

    /** The line that ends the type alias starting at {@code start}: a {@code ;} outside brackets and comments. */
    private static int typeEnd(String[] lines, int start) {
        int depth = 0;
        boolean inComment = false;
        for (int k = start; k < lines.length; k++) {
            String l = lines[k];
            for (int c = 0; c < l.length(); c++) {
                char ch = l.charAt(c);
                if (inComment) {
                    if (ch == '*' && c + 1 < l.length() && l.charAt(c + 1) == '/') {
                        inComment = false;
                        c++;
                    }
                } else if (ch == '/' && c + 1 < l.length() && l.charAt(c + 1) == '*') {
                    inComment = true;
                    c++;
                } else if (ch == '"') {
                    c = l.indexOf('"', c + 1);
                } else if ("({[".indexOf(ch) >= 0) {
                    depth++;
                } else if (")}]".indexOf(ch) >= 0) {
                    depth--;
                } else if (ch == ';' && depth == 0) {
                    return k;
                }
            }
        }
        throw new IllegalArgumentException("api.d.ts: type at line " + (start + 1) + " never ends");
    }

    /** A doc comment as one paragraph. */
    static String prose(String comment) {
        StringBuilder b = new StringBuilder();
        for (String l : comment.split("\n")) {
            String s = l.strip();
            s = s.replaceFirst("^/\\*\\*", "").replaceFirst("\\*/$", "").strip();
            s = s.replaceFirst("^\\*\\s?", "").strip();
            if (!s.isEmpty()) {
                if (b.length() > 0) {
                    b.append(' ');
                }
                b.append(s);
            }
        }
        return b.toString();
    }

    /** {@code path: string, part?: ReadPart} becomes {@code path, part?}. */
    static String paramNames(String params) {
        if (params.isEmpty()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (char ch : (params + ",").toCharArray()) {
            if ("({[".indexOf(ch) >= 0) {
                depth++;
            } else if (")}]".indexOf(ch) >= 0) {
                depth--;
            }
            if (ch == ',' && depth == 0) {
                String p = cur.toString().strip();
                int colon = p.indexOf(':');
                names.add(colon < 0 ? p : p.substring(0, colon).strip());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        return String.join(", ", names);
    }

    /** The first sentence of a comment. */
    static String firstSentence(String doc) {
        Matcher m = Pattern.compile("\\.(\\s|$)").matcher(doc);
        return m.find() ? doc.substring(0, m.start() + 1) : doc;
    }

    static String render(String dts) {
        Parsed p = parse(dts);
        Map<String, Type> types = new LinkedHashMap<>();
        p.types().forEach(t -> types.put(t.name(), t));
        StringBuilder b = new StringBuilder();
        b.append("# The `api` reference\n\n");
        b.append("<!-- Generated from ").append(SOURCE).append(" by scripts/api-reference.sh; ApiReferenceTest checks it. Don't edit it by hand. -->\n\n");
        b.append("Agent code reaches the project only through `api`. The model gets the same declarations in its system prompt. ")
                .append("Each method is listed below, then the types they take and return.\n\n");
        b.append(p.overview()).append("\n\n");
        b.append("## Methods\n\n| Method | What it does |\n| --- | --- |\n");
        for (Method m : p.methods()) {
            b.append("| [`").append(m.name()).append('(').append(paramNames(m.params())).append(")`](#").append(m.name().toLowerCase())
                    .append(") | ").append(cell(firstSentence(m.doc()))).append(" |\n");
        }
        for (Method m : p.methods()) {
            b.append("\n### ").append(m.name()).append("\n\n```ts\napi.").append(m.name()).append('(').append(m.params()).append("): ")
                    .append(m.result()).append("\n```\n\n").append(m.doc()).append('\n');
            List<String> refs = new ArrayList<>();
            Matcher n = NAME.matcher(m.params() + " " + m.result());
            while (n.find()) {
                String name = n.group();
                if (types.containsKey(name) && !refs.contains(name)) {
                    refs.add(name);
                }
            }
            if (!refs.isEmpty()) {
                b.append("\nTypes: ").append(String.join(", ", refs.stream().map(r -> "[`" + r + "`](#" + r.toLowerCase() + ")").toList()))
                        .append('\n');
            }
        }
        b.append("\n## Types\n\nAs declared. Results named `...Json` are JSON strings: call `JSON.parse` on them.\n");
        for (Type t : p.types()) {
            b.append("\n### ").append(t.name()).append("\n\n```ts\n").append(t.text()).append("\n```\n");
        }
        return b.toString();
    }

    private static String cell(String s) {
        return s.replace("|", "\\|");
    }
}
