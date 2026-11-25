package dev.jharness.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Spec 052: the viewer page shows replies as Markdown. Runs the page's {@code markdown.js} in GraalJS with a small
 * fake DOM ({@code fake-dom.js}) and checks the nodes it builds.
 */
class ViewerMarkdownTest {

    static Engine engine;
    static Context js;
    static Value md;

    @BeforeAll
    static void load() throws IOException {
        // GraalJS ships as an isolate library, so the engine spawns one.
        engine = Engine.newBuilder("js").option("engine.SpawnIsolate", "true").out(OutputStream.nullOutputStream())
                .err(OutputStream.nullOutputStream()).build();
        js = Context.newBuilder("js").engine(engine).build();
        js.eval(Source.create("js", resource(ViewerMarkdownTest.class, "fake-dom.js")));
        js.eval(Source.create("js", resource(dev.jharness.web.SessionViewer.class, "markdown.js")));
        md = js.getBindings("js").getMember("md");
    }

    @AfterAll
    static void close() {
        js.close();
        engine.close();
    }

    private static String resource(Class<?> near, String name) throws IOException {
        try (InputStream in = near.getResourceAsStream(name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String md(String text) {
        return md.execute(text).asString();
    }

    @Test
    void inlineMarks() {
        assertEquals("<p>This is <strong>JHarness</strong>, <em>really</em> an <code>api</code> object, <del>not</del> <em>so</em>.</p>",
                md("This is **JHarness**, *really* an `api` object, ~~not~~ _so_."));
        assertEquals("<p>snake_case_name and 2 * 3 * 4</p>", md("snake_case_name and 2 * 3 * 4"));
        assertEquals("<p><code>a **b**</code></p>", md("`a **b**`"), "nothing inside code");
    }

    @Test
    void blocks() {
        assertEquals("<h3 class=\"md-h\">Title</h3><p>one<br>two</p><p>three</p>", md("# Title\none\ntwo\n\nthree"));
        assertEquals("<pre class=\"code\" lang=\"js\"><code>const a = 1;\n**not bold**</code></pre><p>after</p>",
                md("```js\nconst a = 1;\n**not bold**\n```\nafter"));
        assertEquals("<pre class=\"code\"><code>still streaming</code></pre>", md("```\nstill streaming"), "an open fence while a reply streams");
        assertEquals("<blockquote><p>quoted</p></blockquote><hr>", md("> quoted\n\n---"));
    }

    @Test
    void lists() {
        assertEquals("<ul><li><strong>What</strong>: it runs</li><li>two<ul><li>nested</li></ul></li></ul>",
                md("- **What**: it runs\n- two\n  - nested"));
        assertEquals("<ol start=\"3\"><li>three</li><li>four</li></ol>", md("3. three\n4. four"));
    }

    @Test
    void tables() {
        assertEquals("<div class=\"table\"><table><thead><tr><th>A</th><th>B</th></tr></thead>"
                + "<tbody><tr><td><code>x</code></td><td>y</td></tr></tbody></table></div>", md("| A | B |\n| --- | --- |\n| `x` | y |"));
    }

    @Test
    void linksOnlyForWebAddresses() {
        assertEquals("<p>a <a href=\"https://graalvm.github.io/graal-js/\" rel=\"noopener noreferrer\">GraalJS</a> sandbox</p>",
                md("a [GraalJS](https://graalvm.github.io/graal-js/) sandbox"));
        assertEquals("<p>see <a href=\"https://example.com/x\" rel=\"noopener noreferrer\">https://example.com/x</a>.</p>", md("see https://example.com/x."));
        assertEquals("<p>click</p>", md("[click](javascript:alert`1`)"), "no javascript: link");
        assertEquals("<p>x</p>", md("[x](data:text/html,hi)"));
    }

    @Test
    void markupStaysText() {
        assertEquals("<p>&lt;img src=x onerror=alert(1)&gt; <strong>&lt;b&gt;</strong></p>", md("<img src=x onerror=alert(1)> **<b>**"));
    }
}
