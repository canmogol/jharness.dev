package dev.jharness.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.config.ConfigException;
import dev.jharness.config.Settings;
import dev.jharness.events.Event;
import dev.jharness.llm.ReplayProvider;
import dev.jharness.security.PermissionGate;

class SwitchModelTest {

    @TempDir
    Path tmp;

    @Test
    void switchesProfileKeepsConversationAndEmits() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Settings s = Settings.parse("{\"model\":\"a\",\"models\":{\"a\":{\"provider\":\"openai-compatible\",\"baseUrl\":\"http://127.0.0.1:1/v1\",\"model\":\"m-a\"},"
                + "\"b\":{\"provider\":\"anthropic\",\"model\":\"m-b\",\"apiKeyEnv\":\"NOPE_KEY\"}}}", "t");
        List<Event> events = new ArrayList<>();
        try (Session session = Session.open(tmp.resolve("home"), ws, s, Session.Access.gate(PermissionGate.allowAll()),
                (bus, turns) -> ReplayProvider.ofReplies(List.of("hello"), bus, turns))) {
            session.bus().subscribe(events::add);
            session.loop().turn("hi", d -> {});
            session.switchModel("b");
            assertEquals("b", session.profile());
            assertEquals("anthropic", session.loop().provider().provider());
            assertEquals("m-b", session.loop().provider().model());
            assertEquals(2, session.loop().conversation().size(), "conversation kept");
            var e = events.stream().filter(x -> x.type().equals("session.model_changed")).findFirst().orElseThrow();
            assertEquals("b", e.body().get("profile").asText());
            ConfigException bad = assertThrows(ConfigException.class, () -> session.switchModel("c"));
            assertTrue(bad.getMessage().contains("a, b"), bad.getMessage());
            TurnResult r = session.loop().turn("again", d -> {});
            assertEquals("error", r.stopReason(), "the new provider is used (no key set)");
            assertTrue(r.error().contains("NOPE_KEY"), r.error());
        }
    }
}
