package dev.jharness.net;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RulesEditorTest {

    @Test
    void addAndRemoveKeepOtherLines(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve(".jharness/network.rules");
        assertEquals("allow api.example.com:443", RulesEditor.add(f, Rule.Action.ALLOW, "API.example.com:443"));
        Files.writeString(f, "# mine\ndefault deny\n" + Files.readString(f) + "deny *.ads.example.com # no ads");
        assertEquals("deny 10.0.0.0/8", RulesEditor.add(f, Rule.Action.DENY, "10.0.0.0/8"));
        assertEquals("# mine\ndefault deny\nallow api.example.com:443\ndeny *.ads.example.com # no ads\ndeny 10.0.0.0/8\n", Files.readString(f));
        assertEquals(0, RulesEditor.remove(f, "api.example.com"), "the port is part of the target");
        assertEquals(1, RulesEditor.remove(f, "api.example.com:443"));
        assertEquals(1, RulesEditor.remove(f, "*.ADS.example.com"));
        assertEquals("# mine\ndefault deny\ndeny 10.0.0.0/8\n", Files.readString(f));
        assertEquals(2, RulesFile.load(f).rules().size() + 1, "still loads");
        assertThrows(IllegalArgumentException.class, () -> RulesEditor.add(f, Rule.Action.ALLOW, "bad host!"));
        assertEquals(0, RulesEditor.remove(tmp.resolve("missing.rules"), "x.example.com"));
    }
}
