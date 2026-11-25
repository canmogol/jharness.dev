package dev.jharness.cli;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;

import dev.jharness.agent.Session;
import dev.jharness.events.Json;
import dev.jharness.events.SessionLog;
import dev.jharness.web.EventFeed;

/**
 * This run's events as one feed, the stream of {@code --output stream-json} (D-082): what the session log already
 * holds from this run's {@code session.started} on, then each live event, streamed chunks too, each once.
 */
final class LiveFeed {

    private LiveFeed() {}

    /** Calls {@code sink} with each JSON line, in order. */
    static void follow(Session s, java.util.function.Consumer<String> sink) throws IOException {
        Set<String> seen = ConcurrentHashMap.newKeySet();
        Object lock = new Object();
        synchronized (lock) {
            s.bus().subscribe(e -> {
                synchronized (lock) {
                    if (seen.add(e.id())) {
                        sink.accept(e.toJsonLine());
                    }
                }
            });
            List<JsonNode> logged = SessionLog.read(s.logPath());
            int from = 0;
            for (int i = 0; i < logged.size(); i++) {
                if (logged.get(i).path("type").asText().equals("session.started")) {
                    from = i;
                }
            }
            for (JsonNode line : logged.subList(from, logged.size())) {
                if (seen.add(line.path("id").asText())) {
                    sink.accept(Json.write(line));
                }
            }
        }
    }

    static EventFeed of(Session s) throws IOException {
        EventFeed feed = new EventFeed();
        follow(s, feed::add);
        return feed;
    }
}
