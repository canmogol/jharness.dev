package dev.jharness.sandbox;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import dev.jharness.config.Timeouts;
import dev.jharness.events.Json;
import dev.jharness.events.Origin;
import dev.jharness.process.Search;
import dev.jharness.security.FilePolicy;
import dev.jharness.security.PermissionGate;

/** Spec 061: readFile by lines or bytes, and readBytes for binary files and other charsets. */
class ReadsApiTest {

    @TempDir
    Path tmp;
    Fixture fx;
    AgentIsolate iso;
    ApiServices api;

    @BeforeEach
    void setUp() throws Exception {
        fx = new Fixture(tmp);
        FilePolicy policy = new FilePolicy(fx.ws, List.of(), List.of());
        api = new ApiServices(policy, PermissionGate.allowAll(), fx.bus, () -> Origin.main(1), Search.create(fx.ws, fx.runner, Timeouts.defaults(),
                policy.denyList()), fx.git);
        iso = Fixture.isolate(Fixture.profile(Map.of()), fx.bus);
    }

    @AfterEach
    void tearDown() {
        iso.close();
    }

    private RunResult run(String js) {
        return iso.run(js, api);
    }

    private String value(String js) {
        RunResult r = run(js);
        assertEquals("ok", r.status(), js + " -> " + r.error());
        return r.value();
    }

    private String error(String js) {
        RunResult r = run(js);
        assertEquals("error", r.status(), js + " -> " + r.value());
        return r.error();
    }

    /** AC-01, AC-02: a 12 MB file is refused whole and read in parts by lines; past the end is empty. */
    @Test
    void aLargeFileIsReadByLines() throws Exception {
        StringBuilder b = new StringBuilder();
        for (int i = 1; b.length() < 12 << 20; i++) {
            b.append("line ").append(i).append(" ").append("x".repeat(60)).append('\n');
        }
        Files.writeString(fx.root.resolve("big.log"), b);
        assertTrue(error("api.readFile('big.log')").contains("read it in parts with {fromLine, lines} or {offset, length}"));
        assertEquals("line 3 " + "x".repeat(60) + "\nline 4 " + "x".repeat(60) + "\n", value("api.readFile('big.log', {fromLine: 3, lines: 2})"));
        String first = value("api.readFile('big.log', {fromLine: 1})");
        assertTrue(first.length() <= 1 << 20 && first.length() > (1 << 20) - 100 && first.endsWith("\n"), "whole lines up to 1 MB: " + first.length());
        assertEquals("", value("api.readFile('big.log', {fromLine: 100000000, lines: 5})"));
        Files.writeString(fx.root.resolve("one.txt"), "y".repeat((1 << 20) + 10));
        assertTrue(error("api.readFile('one.txt', {fromLine: 1})").contains("line 1 is longer than"));
    }

    /** AC-02: a byte range is cut to whole UTF-8 characters at both ends. */
    @Test
    void byteRangesAreCutToWholeCharacters() throws Exception {
        Files.writeString(fx.root.resolve("u.txt"), "aé€😀b", StandardCharsets.UTF_8); // 1 + 2 + 3 + 4 + 1 bytes
        assertEquals("aé€😀b", value("api.readFile('u.txt', {offset: 0, length: 11})"));
        assertEquals("aé", value("api.readFile('u.txt', {offset: 0, length: 4})"), "the € cut at the end waits");
        assertEquals("€😀b", value("api.readFile('u.txt', {offset: 2, length: 9})"), "the é cut at the front is skipped");
        Files.write(fx.root.resolve("bad.txt"), new byte[] {'o', 'k', (byte) 0xff, 'x'});
        assertTrue(error("api.readFile('bad.txt')").contains("not UTF-8 text; api.readBytes reads it"));
        assertTrue(error("api.readFile('bad.txt', {fromLine: 1})").contains("not UTF-8"));
    }

    /** AC-03: readBytes returns any file's bytes as base64 or hex, in parts, with where to go on. */
    @Test
    void binaryFilesAsBase64OrHex() throws Exception {
        byte[] all = new byte[256];
        for (int i = 0; i < 256; i++) {
            all[i] = (byte) i;
        }
        Files.write(fx.root.resolve("b.bin"), all);
        JsonNode r = Json.parse(value("api.readBytes('b.bin')"));
        assertEquals(256, r.get("size").asInt());
        assertEquals(256, r.get("length").asInt());
        assertTrue(r.get("eof").asBoolean());
        assertEquals("base64", r.get("encoding").asText());
        assertArrayEquals(all, Base64.getDecoder().decode(r.get("content").asText()));
        JsonNode h = Json.parse(value("api.readBytes('b.bin', {offset: 254, length: 10, encoding: 'hex'})"));
        assertEquals("feff", h.get("content").asText());
        assertEquals(256, h.get("nextOffset").asInt());
        assertEquals(2, h.get("length").asInt());
        JsonNode past = Json.parse(value("api.readBytes('b.bin', {offset: 999})"));
        assertEquals(0, past.get("length").asInt());
        assertTrue(past.get("eof").asBoolean());
        Files.write(fx.root.resolve("3mb.bin"), new byte[3 << 20]);
        JsonNode part = Json.parse(value("api.readBytes('3mb.bin', {offset: 1048576})"));
        assertEquals(1 << 20, part.get("length").asInt(), "1 MB per call by default");
        assertEquals(2 << 20, part.get("nextOffset").asInt());
        assertFalse(part.get("eof").asBoolean());
    }

    /** AC-03: a charset decodes strictly; a character cut at the end of the range waits for the next call. */
    @Test
    void charsets() throws Exception {
        Files.write(fx.root.resolve("latin1.txt"), new byte[] {'c', 'a', 'f', (byte) 0xe9});
        assertEquals("café", Json.parse(value("api.readBytes('latin1.txt', {encoding: 'ISO-8859-1'})")).get("content").asText());
        Files.write(fx.root.resolve("cp.txt"), new byte[] {(byte) 0x80, '5'});
        JsonNode w = Json.parse(value("api.readBytes('cp.txt', {encoding: 'windows-1252'})"));
        assertEquals("€5", w.get("content").asText());
        assertEquals("windows-1252", w.get("encoding").asText());
        Files.write(fx.root.resolve("u16.txt"), "héllo".getBytes(StandardCharsets.UTF_16LE));
        JsonNode u = Json.parse(value("api.readBytes('u16.txt', {encoding: 'UTF-16LE', length: 5})"));
        assertEquals("hé", u.get("content").asText());
        assertEquals(4, u.get("length").asInt(), "the cut third character waits");
        assertEquals(4, u.get("nextOffset").asInt());
        Files.write(fx.root.resolve("bad.txt"), new byte[] {'o', (byte) 0xff, 'k'});
        assertTrue(error("api.readBytes('bad.txt', {encoding: 'UTF-8'})").contains("aren't valid UTF-8 near byte 1"));
        assertTrue(error("api.readBytes('bad.txt', {encoding: 'klingon'})").contains("unknown charset"));
    }

    /** AC-04: the usual file rules, and bad options. */
    @Test
    void rulesAndBadOptions() throws Exception {
        Files.writeString(fx.root.resolve(".env"), "SECRET=1\n");
        assertTrue(error("api.readBytes('.env')").contains("deny list"));
        assertTrue(error("api.readFile('.env', {fromLine: 1})").contains("deny list"));
        assertTrue(error("api.readBytes('" + tmp.resolve("outside.bin") + "')").contains("outside the workspace"));
        assertTrue(error("api.readBytes('src')").contains("is a folder"));
        Files.writeString(fx.root.resolve("a.txt"), "a\n");
        assertTrue(error("api.readFile('a.txt', {fromLine: 1, offset: 0})").contains("not both"));
        assertTrue(error("api.readFile('a.txt', {encoding: 'hex'})").contains("doesn't take \"encoding\""));
        assertTrue(error("api.readBytes('a.txt', {length: 2000000})").contains("\"length\" must be a whole number from 1 to 1048576"));
        assertTrue(error("api.readFile('a.txt', {fromLine: 0})").contains("\"fromLine\" must be a whole number from 1"));
        assertTrue(error("api.readBytes('a.txt', {offset: -1})").contains("\"offset\""));
        assertEquals("a\n", value("api.readFile('a.txt')"), "the old call is unchanged");
    }
}
