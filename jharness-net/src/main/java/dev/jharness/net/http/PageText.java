package dev.jharness.net.http;

import java.net.URI;
import java.util.List;
import java.util.Set;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/**
 * Turns an HTML page into Markdown-like text for the model (spec 016, AC-06): headings as {@code #},
 * links as {@code [text](absolute-url)}, list items, fenced {@code pre} blocks, and no scripts,
 * styles, navigation or hidden elements. Uses jsoup (D-054).
 */
public final class PageText {

    /** The page title and its text. */
    public record Result(String title, String text) {}

    private static final Set<String> DROP = Set.of("script", "style", "noscript", "svg", "nav", "header", "footer",
            "form", "template", "iframe", "button", "select", "input", "textarea", "head", "object", "embed", "canvas");
    private static final Set<String> BLOCK = Set.of("p", "div", "section", "article", "main", "aside", "table", "tr",
            "blockquote", "figure", "figcaption", "dl", "dt", "dd", "address", "details", "summary", "body", "html");

    private PageText() {}

    public static Result of(String html, URI base) {
        Document doc = Jsoup.parse(html, base == null ? "" : base.toString());
        String title = doc.title().strip();
        doc.select(String.join(",", DROP)).remove();
        doc.select("[hidden], [aria-hidden=true], [style~=(?i)display:\\s*none]").remove();
        StringBuilder out = new StringBuilder();
        walk(doc.body() != null ? doc.body() : doc, out, 0);
        return new Result(title, tidy(out.toString()));
    }

    private static void walk(Node node, StringBuilder out, int listDepth) {
        for (Node child : node.childNodes()) {
            if (child instanceof TextNode t) {
                String s = t.getWholeText().replaceAll("\\s+", " ");
                char last = out.isEmpty() ? '\n' : out.charAt(out.length() - 1);
                if (s.startsWith(" ") && (last == ' ' || last == '\n')) {
                    s = s.substring(1);
                }
                out.append(s);
            } else if (child instanceof Element e) {
                element(e, out, listDepth);
            }
        }
    }

    private static void element(Element e, StringBuilder out, int listDepth) {
        String tag = e.normalName();
        switch (tag) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                block(out);
                out.append("#".repeat(tag.charAt(1) - '0')).append(' ').append(e.text().strip());
                block(out);
            }
            case "br" -> out.append('\n');
            case "hr" -> {
                block(out);
                out.append("---");
                block(out);
            }
            case "pre" -> {
                block(out);
                Element code = e.selectFirst("code");
                String lang = "";
                if (code != null) {
                    for (String c : code.classNames()) {
                        if (c.startsWith("language-")) {
                            lang = c.substring("language-".length());
                        }
                    }
                }
                out.append("```").append(lang).append('\n').append(e.wholeText().stripTrailing()).append("\n```");
                block(out);
            }
            case "code" -> out.append('`').append(e.text()).append('`');
            case "a" -> {
                String text = e.text().strip();
                String href = e.absUrl("href");
                if (href.isEmpty() || href.startsWith("javascript:")) {
                    out.append(text);
                } else if (text.isEmpty()) {
                    out.append('<').append(href).append('>');
                } else {
                    out.append('[').append(text).append("](").append(href).append(')');
                }
            }
            case "img" -> {
                String alt = e.attr("alt").strip();
                if (!alt.isEmpty()) {
                    out.append("[image: ").append(alt).append(']');
                }
            }
            case "strong", "b" -> {
                out.append("**");
                walk(e, out, listDepth);
                out.append("**");
            }
            case "em", "i" -> {
                out.append('_');
                walk(e, out, listDepth);
                out.append('_');
            }
            case "ul", "ol" -> {
                // A nested list stays inside its item: no blank lines around it.
                boolean nested = listDepth > 0;
                if (nested) {
                    line(out);
                } else {
                    block(out);
                }
                int n = 1;
                for (Element li : e.children()) {
                    if (!li.normalName().equals("li")) {
                        continue;
                    }
                    line(out);
                    out.append("  ".repeat(listDepth)).append(tag.equals("ol") ? n++ + ". " : "- ");
                    walk(li, out, listDepth + 1);
                }
                if (!nested) {
                    block(out);
                }
            }
            case "td", "th" -> {
                walk(e, out, listDepth);
                out.append(" | ");
            }
            default -> {
                boolean b = BLOCK.contains(tag);
                if (b) {
                    block(out);
                }
                walk(e, out, listDepth);
                if (b) {
                    block(out);
                }
            }
        }
    }

    private static void line(StringBuilder out) {
        trimSpaces(out);
        if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }
    }

    private static void block(StringBuilder out) {
        line(out);
        if (out.length() >= 2 && out.charAt(out.length() - 2) != '\n') {
            out.append('\n');
        }
    }

    private static void trimSpaces(StringBuilder out) {
        while (!out.isEmpty() && out.charAt(out.length() - 1) == ' ') {
            out.setLength(out.length() - 1);
        }
    }

    /** Strips trailing spaces on each line and collapses runs of blank lines. */
    private static String tidy(String s) {
        List<String> lines = s.lines().map(String::stripTrailing).toList();
        StringBuilder r = new StringBuilder();
        int blank = 0;
        for (String l : lines) {
            if (l.isEmpty()) {
                if (++blank > 1 || r.isEmpty()) {
                    continue;
                }
            } else {
                blank = 0;
            }
            r.append(l).append('\n');
        }
        return r.toString().strip();
    }
}
