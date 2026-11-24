package dev.jharness.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.config.Settings;
import dev.jharness.events.SessionLog;
import dev.jharness.llm.ReplayProvider;
import dev.jharness.security.FilePolicy;
import dev.jharness.security.Prompter;
import dev.jharness.security.Workspace;

/** Spec 040: {@code @path} mentions attach files and folders under the file policy. */
class MentionsTest {

    @TempDir
    Path tmp;
    Path ws;
    Path mount;
    FilePolicy files;

    @BeforeEach
    void setUp() throws Exception {
        ws = Files.createDirectories(tmp.resolve("ws")).toRealPath();
        mount = Files.createDirectories(tmp.resolve("docs")).toRealPath();
        Files.createDirectories(ws.resolve("src/main"));
        Files.writeString(ws.resolve("src/main/App.java"), "class App {\n  // ```js inside\n}\n");
        Files.writeString(ws.resolve("README.md"), "# Shop\n");
        Files.writeString(ws.resolve(".env"), "TOKEN=secret\n");
        Files.writeString(ws.resolve("my notes.txt"), "spaces\n");
        Files.write(ws.resolve("logo.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 0, 1});
        Files.writeString(mount.resolve("guide.md"), "Read me.\n");
        Files.writeString(tmp.resolve("outside.txt"), "no\n");
        files = new FilePolicy(new Workspace(ws), List.of(mount), List.of());
    }

    @Test
    void findsMentionsAtWordStarts() {
        assertEquals(List.of("src/App.java", "my notes.txt", "README.md,"),
                Mentions.find("@src/App.java and @\"my notes.txt\" but not a@b.com, then @README.md, again @src/App.java"));
        assertEquals(List.of(), Mentions.find("no mentions here"));
    }

    @Test
    void attachesFilesAndFoldersWithFences() {
        Mentions.Result r = Mentions.attach("Fix @src/main/App.java, see @README.md. List @src/main and @\"my notes.txt\"", files, 100_000);
        assertEquals(List.of("src/main/App.java", "README.md", "src/main", "my notes.txt"), r.attached().stream().map(Mentions.Attached::path).toList());
        assertTrue(r.skipped().isEmpty(), r.skipped().toString());
        assertTrue(r.text().startsWith("\n\n# Attached files\n\n"), r.text());
        assertTrue(r.text().contains("## src/main/App.java\n\n````\nclass App {\n  // ```js inside\n}\n````"), "a fence longer than the text's: " + r.text());
        assertTrue(r.text().contains("## src/main/ (folder)\n\n```\nApp.java\n```"), r.text());
        assertEquals(3, r.attached().getFirst().lines());
        assertTrue(r.attached().get(2).folder());
        JsonNode e = r.event();
        assertEquals("README.md", e.get("files").get(1).get("path").asText());
        assertEquals(r.attached().stream().mapToInt(Mentions.Attached::chars).sum(), e.get("chars").asInt());
    }

    @Test
    void theFilePolicyDecides() {
        Mentions.Result r = Mentions.attach("@.env @../outside.txt @" + tmp.resolve("outside.txt") + " @" + mount.resolve("guide.md")
                + " @logo.png @missing/File.java @Override @team", files, 100_000);
        assertEquals(List.of(mount.resolve("guide.md").toString()), r.attached().stream().map(Mentions.Attached::path).toList(),
                "a read-only mount can be attached");
        List<String> why = r.skipped().stream().map(s -> s.mention() + ": " + s.reason()).toList();
        assertEquals(5, why.size(), why.toString());
        assertTrue(why.get(0).startsWith(".env: .env is on the deny list"), why.toString());
        assertTrue(why.get(1).contains("outside the workspace"), why.toString());
        assertTrue(why.get(2).contains("outside the workspace"), why.toString());
        assertEquals("logo.png: a binary file", why.get(3));
        assertEquals("missing/File.java: not found", why.get(4));
        assertFalse(r.text().contains("TOKEN=secret"));
        assertFalse(r.text().contains("no\n"));
    }

    @Test
    void theDenyListHidesFolderEntries() {
        Mentions.Result r = Mentions.attach("@.", files, 100_000);
        assertTrue(r.text().contains("README.md\n"), r.text());
        assertTrue(r.text().contains("src/\n"), r.text());
        assertFalse(r.text().contains(".env"), r.text());
    }

    @Test
    void bigFilesAreCutAndTheBudgetHolds() throws Exception {
        String line = "x".repeat(99) + "\n";
        Files.writeString(ws.resolve("big.txt"), line.repeat(1000));
        Files.writeString(ws.resolve("b.txt"), line.repeat(20));
        Mentions.Result r = Mentions.attach("@big.txt @b.txt @README.md", files, 51_000);
        Mentions.Attached big = r.attached().getFirst();
        assertTrue(big.truncated());
        assertEquals(Mentions.FILE_CHARS, big.chars(), "cut at a line break");
        assertTrue(r.text().contains("(cut after 50,000 characters of a 100,000-byte file; read the rest with api.readFile)"), r.text());
        Mentions.Attached b = r.attached().get(1);
        assertTrue(b.truncated());
        assertEquals(1000, b.chars(), "what was left of the budget");
        assertEquals("README.md: the attachments reached 51,000 characters", r.skipped().getFirst().mention() + ": " + r.skipped().getFirst().reason());
        assertEquals(28_672, Mentions.budget(32_768), "a quarter of a small window");
        assertEquals(Mentions.TOTAL_CHARS, Mentions.budget(1_000_000));
    }

    @Test
    void completesPathsUnderThePolicy() {
        assertEquals(List.of("@src/"), Mentions.complete("@s", files));
        assertEquals(List.of("@src/main/App.java"), Mentions.complete("@src/main/", files));
        assertEquals(List.of(), Mentions.complete("@.e", files), "deny-listed");
        assertEquals(List.of(), Mentions.complete("@../", files));
        assertEquals(List.of("@README.md"), Mentions.complete("@R", files));
    }

    @Test
    void aTurnCarriesTheAttachmentButLogsTheTypedPrompt() throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home"));
        ReplayProvider[] replay = new ReplayProvider[1];
        Path log;
        try (Session s = Session.open(home, ws, Settings.defaults(), Session.Access.prompter(op -> Prompter.Answer.ONCE),
                (bus, turns) -> replay[0] = ReplayProvider.ofReplies(List.of("done", "done"), bus, turns))) {
            s.turn("Explain @README.md and @nope.txt", d -> {});
            s.turn("no mentions", d -> {});
            log = s.logPath();
        }
        String sent = replay[0].requests().getFirst().messages().getLast().content();
        assertTrue(sent.startsWith("Explain @README.md and @nope.txt\n\n# Attached files"), sent);
        assertTrue(sent.contains("## README.md\n\n```\n# Shop\n```"), sent);
        assertEquals("no mentions", replay[0].requests().get(1).messages().getLast().content());
        List<JsonNode> events = SessionLog.read(log);
        JsonNode started = events.stream().filter(e -> e.get("type").asText().equals("turn.started")).findFirst().orElseThrow();
        assertEquals("Explain @README.md and @nope.txt", started.get("prompt").asText());
        List<JsonNode> attached = events.stream().filter(e -> e.get("type").asText().equals("prompt.attached")).toList();
        assertEquals(1, attached.size(), "only a turn with mentions");
        assertEquals("README.md", attached.getFirst().get("files").get(0).get("path").asText());
        assertEquals("nope.txt", attached.getFirst().get("skipped").get(0).get("mention").asText());
        assertEquals(1, attached.getFirst().get("turn").asInt());
    }
}
