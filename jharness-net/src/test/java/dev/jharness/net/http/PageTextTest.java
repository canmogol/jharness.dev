package dev.jharness.net.http;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;

import org.junit.jupiter.api.Test;

/** The HTML-to-text conversion for {@code api.fetch} (spec 016, AC-06). */
class PageTextTest {

    private static PageText.Result of(String html) {
        return PageText.of(html, URI.create("https://docs.example.com/guide/start.html"));
    }

    @Test
    void headingsLinksListsAndCode() {
        var r = of("""
                <html><head><title> Getting started </title></head><body>
                <h1>Start</h1><p>Install <b>JHarness</b> and read <a href="../api/index.html">the API</a>
                or <a href="https://other.example/x">this</a>.</p>
                <h2>Steps</h2><ol><li>Download</li><li>Run <code>jharness</code></li></ol>
                <ul><li>a<ul><li>nested</li></ul></li><li>b</li></ul>
                <pre><code class="language-java">int x = 1;
                int y = 2;</code></pre>
                <p>Line<br>break</p><hr><img src="x.png" alt="Diagram"></body></html>
                """);
        assertEquals("Getting started", r.title());
        String t = r.text();
        assertTrue(t.startsWith("# Start\n\nInstall **JHarness** and read [the API](https://docs.example.com/api/index.html)"), t);
        assertTrue(t.contains("[this](https://other.example/x)"), t);
        assertTrue(t.contains("## Steps\n\n1. Download\n2. Run `jharness`"), t);
        assertTrue(t.contains("- a\n  - nested"), t);
        assertTrue(t.contains("\n- b"), t);
        assertTrue(t.contains("```java\nint x = 1;\nint y = 2;\n```"), t);
        assertTrue(t.contains("Line\nbreak"), t);
        assertTrue(t.contains("---"), t);
        assertTrue(t.contains("[image: Diagram]"), t);
        assertFalse(t.contains("\n\n\n"), t);
    }

    @Test
    void noScriptsMenusFormsOrHiddenText() {
        var r = of("""
                <html><head><style>p{}</style><script>alert(1)</script></head><body>
                <header>Site header</header><nav><a href="/">Home</a></nav>
                <main><p>Body text</p><div hidden>hidden one</div><span aria-hidden="true">hidden two</span>
                <div style="display: none">hidden three</div><noscript>enable js</noscript>
                <form><input value="x"><button>Go</button></form><svg><text>svg</text></svg>
                <a href="javascript:evil()">click</a></main><footer>Footer</footer>
                <template><p>tpl</p></template><iframe src="x"></iframe></body></html>
                """);
        String t = r.text();
        assertTrue(t.contains("Body text"), t);
        assertTrue(t.contains("click") && !t.contains("javascript:"), t);
        for (String gone : java.util.List.of("alert", "Site header", "Home", "hidden one", "hidden two", "hidden three", "enable js",
                "Go", "svg", "Footer", "tpl", "p{}")) {
            assertFalse(t.contains(gone), gone + " in: " + t);
        }
    }

    @Test
    void brokenHtmlAndNoTitle() {
        var r = of("<p>unclosed <b>bold <i>both</p><p>next");
        assertEquals("", r.title());
        assertTrue(r.text().contains("unclosed **bold _both_**"), r.text());
        // Like a browser, jsoup carries the unclosed <b><i> into the next paragraph.
        assertTrue(r.text().endsWith("next_**"), r.text());
        assertEquals("", of("").text());
    }
}
