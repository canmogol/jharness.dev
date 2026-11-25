package dev.jharness.web;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Spec 048: the viewer serves only GET, only on loopback, only with its token and a matching Host and Origin. */
class SessionViewerTest {

    @TempDir
    Path tmp;
    SessionViewer viewer;
    // Test code talks to the viewer directly; product code has no client here.
    final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(5)).build();

    @AfterEach
    void tearDown() {
        if (viewer != null) {
            viewer.close();
        }
    }

    private HttpResponse<String> get(String pathAndQuery) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + viewer.port() + pathAndQuery)).timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesThePageOnlyWithTheToken() throws Exception {
        viewer = SessionViewer.start(new EventFeed(), 0);
        assertTrue(viewer.url().matches("http://127\\.0\\.0\\.1:\\d+/\\?t=[A-Za-z0-9_-]{43}"), viewer.url());
        String t = viewer.token();
        var page = get("/?t=" + t);
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("<script src=\"app.js?t=" + t + "\""), page.body());
        assertEquals(SessionViewer.CSP, page.headers().firstValue("Content-Security-Policy").orElse(""));
        assertEquals("no-referrer", page.headers().firstValue("Referrer-Policy").orElse(""));
        assertEquals("nosniff", page.headers().firstValue("X-Content-Type-Options").orElse(""));
        assertEquals(200, get("/app.js?t=" + t).statusCode());
        assertTrue(get("/app.css?t=" + t).headers().firstValue("Content-Type").orElse("").startsWith("text/css"));
        assertEquals(403, get("/").statusCode(), "no token");
        assertEquals(403, get("/?t=" + t.substring(1) + "x").statusCode(), "wrong token");
        assertEquals(403, get("/app.js").statusCode(), "assets need it too");
        assertEquals(404, get("/../../etc/passwd?t=" + t).statusCode());
        var post = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + viewer.port() + "/?t=" + t))
                .POST(HttpRequest.BodyPublishers.ofString("x")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(405, post.statusCode(), "read-only");
    }

    /**
     * Spec 050 regression: a browser loads the script and stylesheet exactly as the page links them. Before the fix the
     * links had no token, so both got 403 and the page stayed unstyled at "connecting…".
     */
    @Test
    void thePagesOwnLinksLoad() throws Exception {
        viewer = SessionViewer.start(new EventFeed(), 0);
        URI page = URI.create(viewer.url());
        String html = get(page.getRawPath() + "?" + page.getRawQuery()).body();
        var m = java.util.regex.Pattern.compile("(?:src|href)=\"([^\"]+)\"").matcher(html);
        List<String> links = new ArrayList<>();
        while (m.find()) {
            links.add(m.group(1));
        }
        assertEquals(2, links.size(), html);
        for (String link : links) {
            URI u = page.resolve(link);
            var res = get(u.getRawPath() + "?" + u.getRawQuery());
            assertEquals(200, res.statusCode(), link + " as the browser asks for it");
        }
        assertTrue(links.stream().allMatch(l -> l.endsWith("?t=" + viewer.token())), links.toString());
    }

    @Test
    void hostAndOriginMustBeThisViewer() throws Exception {
        viewer = SessionViewer.start(new EventFeed(), 0);
        int p = viewer.port();
        URI ok = URI.create("/?t=" + viewer.token());
        assertNull(viewer.refused("GET", "127.0.0.1:" + p, null, ok));
        assertNull(viewer.refused("GET", "localhost:" + p, "http://localhost:" + p, ok));
        assertNull(viewer.refused("GET", "127.0.0.1:" + p, "http://127.0.0.1:" + p, ok));
        assertEquals("403 wrong Host", viewer.refused("GET", "evil.example:" + p, null, ok), "DNS rebinding");
        assertEquals("403 wrong Host", viewer.refused("GET", null, null, ok));
        assertEquals("403 wrong Origin", viewer.refused("GET", "127.0.0.1:" + p, "http://evil.example", ok));
        assertEquals("403 wrong Origin", viewer.refused("GET", "127.0.0.1:" + p, "null", ok));
        assertTrue(viewer.refused("PUT", "127.0.0.1:" + p, null, ok).startsWith("405"));
        assertTrue(viewer.refused("GET", "127.0.0.1:" + p, null, URI.create("/events?x=1&t=" + viewer.token())) == null, "token anywhere in the query");
    }

    @Test
    void eventsStreamTheBacklogThenLiveLines() throws Exception {
        EventFeed feed = new EventFeed();
        feed.add("{\"type\":\"session.started\",\"n\":1}");
        feed.add("{\"type\":\"turn.started\",\"prompt\":\"a\\nb\"}");
        viewer = SessionViewer.start(feed, 0);
        var res = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + viewer.port() + "/events?t=" + viewer.token())).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, res.statusCode());
        assertTrue(res.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
        try (InputStream in = res.body(); BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            List<String> data = new ArrayList<>();
            readData(r, data, 2);
            assertEquals(List.of("{\"type\":\"session.started\",\"n\":1}", "{\"type\":\"turn.started\",\"prompt\":\"a\\nb\"}"), data);
            feed.add("{\"type\":\"model.delta\",\"text\":\"hi\"}");
            readData(r, data, 3);
            assertEquals("{\"type\":\"model.delta\",\"text\":\"hi\"}", data.get(2));
        }
    }

    private static void readData(BufferedReader r, List<String> data, int want) throws Exception {
        String line;
        while (data.size() < want && (line = r.readLine()) != null) {
            if (line.startsWith("data: ")) {
                data.add(line.substring(6));
            }
        }
    }

    @Test
    void theTailFollowsALog() throws Exception {
        Path f = tmp.resolve("s.jsonl");
        Files.writeString(f, "{\"a\":1}\n{\"a\":2}\n{\"a\":");
        EventFeed feed = new EventFeed();
        try (LogTail t = LogTail.start(f, feed)) {
            assertEquals(2, feed.size(), "only complete lines");
            Files.writeString(f, "3}\n", java.nio.file.StandardOpenOption.APPEND);
            long until = System.currentTimeMillis() + 5000;
            while (feed.size() < 3 && System.currentTimeMillis() < until) {
                Thread.sleep(50);
            }
            assertEquals(3, feed.size());
            List<String> seen = new ArrayList<>();
            feed.attach(seen::add);
            assertEquals(List.of("{\"a\":1}", "{\"a\":2}", "{\"a\":3}"), seen);
        }
        assertThrows(java.io.IOException.class, () -> LogTail.start(tmp.resolve("missing.jsonl"), new EventFeed()));
    }

    @Test
    void thePageNeverTurnsEventTextIntoMarkup() throws Exception {
        String js;
        try (InputStream in = SessionViewer.class.getResourceAsStream("app.js")) {
            js = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String sink : List.of("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(", "new Function")) {
            assertFalse(js.contains(sink), "app.js uses " + sink);
        }
        String html;
        try (InputStream in = SessionViewer.class.getResourceAsStream("index.html")) {
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertFalse(html.contains("<script>") || html.contains("onclick") || html.contains("http"), "no inline script or outside resource");
    }

    @Test
    void theBacklogIsCapped() {
        EventFeed feed = new EventFeed();
        for (int i = 0; i < EventFeed.MAX_BACKLOG + 5; i++) {
            feed.add("{}");
        }
        assertEquals(EventFeed.MAX_BACKLOG, feed.size());
        assertEquals(5, feed.dropped());
    }
}
