package dev.jharness.net;

import static dev.jharness.net.RulesFileTest.ip;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class NetworkRulesTest {

    private static final java.net.InetAddress PUBLIC = ip("93.184.216.34");

    private static NetworkRules rules(String general, String project, boolean trusted, String... automatic) throws Exception {
        List<Rule> auto = java.util.Arrays.stream(automatic).map(a -> RulesFile.rule(Rule.Action.ALLOW, a, "model profile")).toList();
        return new NetworkRules(RulesFile.parse(general, Path.of("general")), RulesFile.parse(project, Path.of("project")), trusted, auto);
    }

    private static Rule.Action decide(NetworkRules r, String host, int port, java.net.InetAddress ip) {
        return r.decide(host, port, ip).action();
    }

    @Test
    void noRulesAsk() throws Exception {
        assertEquals(Rule.Action.ASK, decide(rules("", "", true), "example.com", 443, PUBLIC));
        assertEquals("no rule matched", rules("", "", true).decide("example.com", 443, PUBLIC).reason());
    }

    @Test
    void hostsSuffixesAndPorts() throws Exception {
        NetworkRules r = rules("default deny\nallow *.github.com\nallow api.example.com:443\ndeny bad.github.com", "", true);
        assertEquals(Rule.Action.ALLOW, decide(r, "api.github.com", 443, PUBLIC));
        assertEquals(Rule.Action.DENY, decide(r, "github.com", 443, PUBLIC), "*.suffix doesn't match the apex");
        assertEquals(Rule.Action.DENY, decide(r, "bad.github.com", 443, PUBLIC), "deny beats allow in one file");
        assertEquals(Rule.Action.ALLOW, decide(r, "API.Example.com", 443, PUBLIC), "hosts compare without case");
        assertEquals(Rule.Action.DENY, decide(r, "api.example.com", 80, PUBLIC), "port must match");
        assertTrue(r.decide("bad.github.com", 443, PUBLIC).reason().contains("deny bad.github.com (general:4)"));
    }

    @Test
    void addressRulesMatchTheResolvedIp() throws Exception {
        NetworkRules r = rules("deny 93.184.216.0/24\nallow 1.1.1.1", "", true);
        assertEquals(Rule.Action.DENY, decide(r, "example.com", 443, PUBLIC));
        assertEquals(Rule.Action.ALLOW, decide(r, "one.one.one.one", 443, ip("1.1.1.1")));
    }

    @Test
    void builtInDeniesUnlessNamedExplicitly() throws Exception {
        for (String a : List.of("127.0.0.1", "10.1.2.3", "172.20.0.1", "192.168.68.140", "169.254.169.254", "100.64.0.1", "0.0.0.0",
                "::1", "fd00::1", "fe80::1")) {
            NetworkRules r = rules("default allow\nallow *.example.com", "", true);
            NetworkRules.Decision d = r.decide("x.example.com", 80, ip(a));
            assertEquals(Rule.Action.DENY, d.action(), a + ": a wildcard or the default doesn't open internal addresses");
            assertTrue(d.reason().startsWith("built-in deny"), d.reason());
        }
        assertEquals(Rule.Action.ALLOW, decide(rules("allow 192.168.68.140", "", true), "lan", 1234, ip("192.168.68.140")));
        assertEquals(Rule.Action.ALLOW, decide(rules("allow localhost:11434", "", true), "localhost", 11434, ip("127.0.0.1")));
        assertEquals(Rule.Action.DENY, decide(rules("allow localhost:11434", "", true), "localhost", 8080, ip("127.0.0.1")));
        assertEquals(Rule.Action.DENY, decide(rules("allow 192.168.0.0/16\ndeny 192.168.1.1", "", true), "r", 80, ip("192.168.1.1")));
    }

    @Test
    void modelEndpointsAreAllowedAutomatically() throws Exception {
        NetworkRules r = rules("default deny", "", false, "192.168.68.140:1234", "api.anthropic.com:443");
        assertEquals(Rule.Action.ALLOW, decide(r, "192.168.68.140", 1234, ip("192.168.68.140")));
        assertEquals(Rule.Action.ALLOW, decide(r, "api.anthropic.com", 443, PUBLIC));
        assertEquals(Rule.Action.DENY, decide(r, "192.168.68.140", 22, ip("192.168.68.140")));
        assertEquals(Rule.Action.DENY, decide(rules("deny api.anthropic.com", "", false, "api.anthropic.com:443"), "api.anthropic.com", 443, PUBLIC),
                "a user deny still wins");
    }

    @Test
    void trustedProjectComesFirstAndCanOverride() throws Exception {
        NetworkRules r = rules("default deny\ndeny npm.example.com", "default allow\nallow npm.example.com\ndeny tracker.example.com", true);
        assertEquals(Rule.Action.ALLOW, decide(r, "npm.example.com", 443, PUBLIC), "project allow overrides a general deny");
        assertEquals(Rule.Action.DENY, decide(r, "tracker.example.com", 443, PUBLIC));
        assertEquals(Rule.Action.ALLOW, decide(r, "other.example.com", 443, PUBLIC), "the project default wins when trusted");
    }

    @Test
    void untrustedProjectOnlyTightens() throws Exception {
        NetworkRules r = rules("default deny\nallow ok.example.com", "default allow\nallow evil.example.com\nallow 10.0.0.1\ndeny ok.example.com", false);
        assertEquals(Rule.Action.DENY, decide(r, "evil.example.com", 443, PUBLIC), "untrusted project allows don't count");
        assertEquals(Rule.Action.DENY, decide(r, "other.example.com", 443, PUBLIC), "untrusted project default doesn't count");
        assertEquals(Rule.Action.DENY, decide(r, "ok.example.com", 443, PUBLIC), "untrusted project denies do count");
        assertEquals(Rule.Action.DENY, decide(r, "x", 80, ip("10.0.0.1")), "untrusted project can't open internal addresses");
    }
}
