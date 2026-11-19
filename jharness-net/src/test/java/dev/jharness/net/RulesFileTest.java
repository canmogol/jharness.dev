package dev.jharness.net;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.config.ConfigException;

class RulesFileTest {

    private static final Path F = Path.of("/home/u/.jharness/network.rules");

    @Test
    void parsesTheD025Example() throws Exception {
        RulesFile f = RulesFile.parse("""
                # ~/.jharness/network.rules
                default ask
                allow registry.npmjs.org
                allow repo.maven.apache.org
                allow *.github.com
                allow 10.20.0.0/16
                allow build.internal.example.com:8443   # trailing comment
                deny  169.254.169.254
                deny  *.corp.example.com
                allow [::1]:11434
                deny fc00::/7
                """, F);
        assertEquals(Optional.of(Rule.Action.ASK), f.defaultAction());
        List<String> lines = f.rules().stream().map(Rule::line).toList();
        assertEquals(List.of("allow registry.npmjs.org", "allow repo.maven.apache.org", "allow *.github.com", "allow 10.20.0.0/16",
                "allow build.internal.example.com:8443", "deny 169.254.169.254", "deny *.corp.example.com", "allow [::1]:11434",
                "deny fc00::/7"), lines);
        assertEquals(F + ":3", f.rules().getFirst().source());
        assertEquals(8443, f.rules().get(4).port());
    }

    @Test
    void badLinesNameTheFileAndLine() {
        assertBad("allow", ":1: expected");
        assertBad("\n\npermit example.com", ":3: unknown keyword \"permit\"");
        assertBad("default maybe", "default must be ask, allow or deny");
        assertBad("default ask\ndefault deny", ":2: a second default line");
        assertBad("allow exa mple.com", ":1: expected");
        assertBad("allow example.com:99999", "bad port");
        assertBad("allow 10.0.0.0/33", "bad address or range");
        assertBad("allow under_score.com", "bad host");
        assertBad("allow *.", "bad host");
        assertBad("allow a*.example.com", "bad host");
        assertBad("allow [::1", "missing ]");
    }

    private static void assertBad(String text, String part) {
        ConfigException e = assertThrows(ConfigException.class, () -> RulesFile.parse(text, F));
        assertTrue(e.getMessage().startsWith(F.toString()) && e.getMessage().contains(part), e.getMessage());
    }

    @Test
    void missingFileIsEmpty(@TempDir Path tmp) throws Exception {
        RulesFile f = RulesFile.load(tmp.resolve("nope.rules"));
        assertTrue(f.rules().isEmpty());
        assertTrue(f.defaultAction().isEmpty());
        Files.writeString(tmp.resolve("x.rules"), "deny example.com\n");
        assertEquals(1, RulesFile.load(tmp.resolve("x.rules")).rules().size());
    }

    @Test
    void ranges() {
        Range r = Range.parse("10.20.0.0/16");
        assertTrue(r.contains(ip("10.20.255.1")));
        assertFalse(r.contains(ip("10.21.0.1")));
        assertFalse(r.contains(ip("::1")), "IPv4 and IPv6 never match");
        assertTrue(Range.parse("fc00::/7").contains(ip("fd12::5")));
        assertTrue(Range.parse("172.16.0.0/12").contains(ip("172.31.0.1")));
        assertFalse(Range.parse("172.16.0.0/12").contains(ip("172.32.0.1")));
        assertTrue(Range.parse("1.2.3.4").contains(ip("1.2.3.4")));
        assertTrue(Range.parse("0.0.0.0/0").contains(ip("8.8.8.8")));
    }

    static java.net.InetAddress ip(String s) {
        return java.net.InetAddress.ofLiteral(s);
    }
}
