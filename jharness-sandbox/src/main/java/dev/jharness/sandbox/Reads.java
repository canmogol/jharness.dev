package dev.jharness.sandbox;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.jharness.events.Json;

/**
 * Parts of files for {@code api.readFile(path, options)} and {@code api.readBytes} (spec 061): by lines or bytes, as
 * text in a charset, or as base64 or hex. One call returns at most {@link #MAX} bytes of the file; the file may be any
 * size.
 */
final class Reads {

    static final int MAX = (int) HostApi.MAX_READ_BYTES;
    /** The most lines one call may ask for. */
    static final int MAX_LINES = 100_000;

    private static final Set<String> TEXT_KEYS = Set.of("fromLine", "lines", "offset", "length");
    private static final Set<String> BYTE_KEYS = Set.of("offset", "length", "encoding");

    private Reads() {
    }

    static void checkTextOptions(ObjectNode o) {
        keys(o, TEXT_KEYS, "readFile");
        boolean byLines = o.has("fromLine") || o.has("lines");
        boolean byBytes = o.has("offset") || o.has("length");
        if (byLines && byBytes) {
            throw new IllegalArgumentException("readFile takes {fromLine, lines} or {offset, length}, not both");
        }
        number(o, "fromLine", 1, Long.MAX_VALUE);
        number(o, "lines", 1, MAX_LINES);
        number(o, "offset", 0, Long.MAX_VALUE);
        number(o, "length", 1, MAX);
    }

    static void checkByteOptions(ObjectNode o) {
        keys(o, BYTE_KEYS, "readBytes");
        number(o, "offset", 0, Long.MAX_VALUE);
        number(o, "length", 1, MAX);
        if (o.has("encoding")) {
            JsonNode e = o.get("encoding");
            if (!e.isTextual() || e.asText().isBlank()) {
                throw new IllegalArgumentException("\"encoding\" must be base64, hex or a charset name such as ISO-8859-1");
            }
            String n = e.asText();
            if (!n.equalsIgnoreCase("base64") && !n.equalsIgnoreCase("hex")) {
                charset(n);
            }
        }
    }

    /** " (lines 10 to 59)", " (bytes 0 to 1048575)" or "", for the api.call summary. */
    static String describe(ObjectNode o) {
        if (o.has("fromLine") || o.has("lines")) {
            long from = o.path("fromLine").asLong(1);
            return o.has("lines") ? " (lines " + from + " to " + (from + o.get("lines").asLong() - 1) + ")" : " (from line " + from + ")";
        }
        if (o.has("offset") || o.has("length")) {
            long off = o.path("offset").asLong(0);
            return " (bytes from " + off + ", up to " + o.path("length").asInt(MAX) + ")";
        }
        return o.has("encoding") ? " (" + o.get("encoding").asText() + ")" : "";
    }

    /** UTF-8 text of some lines, or of a byte range cut to whole characters. */
    static String text(Path p, String rel, ObjectNode o) throws IOException {
        if (o.has("offset") || o.has("length")) {
            long offset = o.path("offset").asLong(0);
            Chunk c = decode(p, rel, offset, o.path("length").asInt(MAX), StandardCharsets.UTF_8, true);
            return c.text();
        }
        long from = o.path("fromLine").asLong(1);
        long want = o.has("lines") ? o.get("lines").asLong() : Long.MAX_VALUE;
        CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        StringBuilder out = new StringBuilder();
        long bytes = 0;
        long n = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(Files.newInputStream(p), dec))) {
            String line;
            long no = 0;
            while (n < want && (line = r.readLine()) != null) {
                no++;
                if (no < from) {
                    continue;
                }
                long size = line.getBytes(StandardCharsets.UTF_8).length + 1L;
                if (bytes + size > MAX) {
                    if (n == 0) {
                        throw new IllegalArgumentException(rel + " line " + no + " is longer than " + MAX
                                + " bytes; read it with {offset, length} or api.readBytes");
                    }
                    break;
                }
                out.append(line).append('\n');
                bytes += size;
                n++;
            }
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException(rel + " is not UTF-8 text; api.readBytes reads it as base64 or hex, or in another charset");
        }
        return out.toString();
    }

    /** {path, size, offset, length, nextOffset, eof, encoding, content}. */
    static ObjectNode bytes(Path p, String rel, ObjectNode o) throws IOException {
        long offset = o.path("offset").asLong(0);
        int length = o.path("length").asInt(MAX);
        String enc = o.path("encoding").asText("base64");
        long size = Files.size(p);
        ObjectNode r = Json.object().put("path", rel).put("size", size).put("offset", offset);
        if (enc.equalsIgnoreCase("base64") || enc.equalsIgnoreCase("hex")) {
            byte[] b = read(p, offset, length, size);
            r.put("length", b.length).put("nextOffset", offset + b.length).put("eof", offset + b.length >= size)
                    .put("encoding", enc.toLowerCase())
                    .put("content", enc.equalsIgnoreCase("base64") ? Base64.getEncoder().encodeToString(b) : HexFormat.of().formatHex(b));
            return r;
        }
        Charset cs = charset(enc);
        Chunk c = decode(p, rel, offset, length, cs, false);
        return r.put("length", c.bytes()).put("nextOffset", offset + c.bytes()).put("eof", offset + c.bytes() >= size)
                .put("encoding", cs.name()).put("content", c.text());
    }

    private record Chunk(String text, int bytes) {}

    /**
     * Decodes a byte range strictly. A character cut at the end is left for the next call; with {@code skipLead}, UTF-8
     * continuation bytes at the start (a cut at the front) are skipped.
     */
    private static Chunk decode(Path p, String rel, long offset, int length, Charset cs, boolean skipLead) throws IOException {
        long size = Files.size(p);
        byte[] b = read(p, offset, length, size);
        int start = 0;
        if (skipLead && cs.equals(StandardCharsets.UTF_8)) {
            while (start < b.length && start < 3 && (b[start] & 0xC0) == 0x80) {
                start++;
            }
        }
        boolean end = offset + b.length >= size;
        CharsetDecoder dec = cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(b, start, b.length - start);
        CharBuffer out = CharBuffer.allocate((int) Math.ceil((b.length - start) * (double) dec.maxCharsPerByte()) + 4);
        // Before the end of the file, a character cut at the end of the range stays in the buffer (underflow), not an error;
        // the next call starts at it.
        CoderResult res = dec.decode(in, out, end);
        if (res.isError()) {
            throw new IllegalArgumentException(rel + " has bytes that aren't valid " + cs.name() + " near byte " + (offset + in.position())
                    + "; read it with api.readBytes as base64 or hex, or in another charset");
        }
        if (end) {
            res = dec.flush(out);
            if (res.isError()) {
                throw new IllegalArgumentException(rel + " ends in the middle of a " + cs.name() + " character");
            }
        }
        out.flip();
        return new Chunk(out.toString(), in.position());
    }

    private static byte[] read(Path p, long offset, int length, long size) throws IOException {
        if (offset >= size) {
            return new byte[0];
        }
        int n = (int) Math.min(length, size - offset);
        ByteBuffer buf = ByteBuffer.allocate(n);
        try (FileChannel ch = FileChannel.open(p, StandardOpenOption.READ)) {
            ch.position(offset);
            while (buf.hasRemaining() && ch.read(buf) >= 0) {
                // Reads until the range is full or the file ends.
            }
        }
        byte[] b = new byte[buf.position()];
        buf.flip();
        buf.get(b);
        return b;
    }

    private static Charset charset(String name) {
        try {
            return Charset.forName(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown charset \"" + name + "\"; use a name such as UTF-8, ISO-8859-1, windows-1252 or UTF-16LE,"
                    + " or base64 or hex for binary files");
        }
    }

    private static void keys(ObjectNode o, Set<String> allowed, String api) {
        o.fieldNames().forEachRemaining(k -> {
            if (!allowed.contains(k)) {
                throw new IllegalArgumentException(api + " doesn't take \"" + k + "\" (it takes " + String.join(", ", new java.util.TreeSet<>(allowed)) + ")");
            }
        });
    }

    private static void number(ObjectNode o, String k, long min, long max) {
        if (!o.has(k)) {
            return;
        }
        JsonNode v = o.get(k);
        if (!v.isIntegralNumber() || v.asLong() < min || v.asLong() > max) {
            throw new IllegalArgumentException("\"" + k + "\" must be a whole number from " + min + (max == Long.MAX_VALUE ? " up" : " to " + max));
        }
    }
}
