package dev.jharness.sandbox;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Spec 065: docs/api.md is what {@link ApiReference} makes of api.d.ts. Run scripts/api-reference.sh after changing
 * api.d.ts; it sets jharness.writeApiReference, and this test writes the file instead of failing.
 */
class ApiReferenceTest {

    static final Path DOC = Path.of("..", "docs", "api.md");

    @Test
    void theDocMatchesTheDeclarations() throws Exception {
        String expected = ApiReference.render(AgentIsolate.apiDeclaration());
        if (Boolean.getBoolean("jharness.writeApiReference")) {
            Files.writeString(DOC, expected, StandardCharsets.UTF_8);
        }
        assertTrue(Files.exists(DOC), "docs/api.md is missing: run scripts/api-reference.sh");
        assertEquals(expected, Files.readString(DOC, StandardCharsets.UTF_8),
                "docs/api.md differs from api.d.ts: run scripts/api-reference.sh");
    }

    @Test
    void everyMethodAndTypeIsRead() {
        ApiReference.Parsed p = ApiReference.parse(AgentIsolate.apiDeclaration());
        assertEquals(22, p.methods().size(), p.methods().toString());
        for (ApiReference.Method m : p.methods()) {
            assertFalse(m.doc().isBlank(), m.name() + " has no comment in api.d.ts");
        }
        assertTrue(p.types().stream().anyMatch(t -> t.name().equals("GitRequest") && t.text().contains("op: \"clone\"")),
                "a multi-line type is read whole");
        assertTrue(p.overview().startsWith("The JHarness api."), p.overview());
    }

    @Test
    void piecesOfTheRendering() {
        assertEquals("path, part?", ApiReference.paramNames("path: string, part?: ReadPart"));
        assertEquals("request", ApiReference.paramNames("request: { url: string; timeoutMs?: number } | string"));
        assertEquals("", ApiReference.paramNames(""));
        assertEquals("One. ", ApiReference.firstSentence("One. Two.") + " ");
        assertEquals("a b", ApiReference.prose("/**\n * a\n *  b\n */"));
    }
}
