package dev.jharness.llm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Minimal server-sent events reader: {@code event:} and {@code data:} lines, events end at a blank line. */
final class Sse {

    interface Handler {
        /** @return false to stop reading */
        boolean onEvent(String event, String data) throws ModelException;
    }

    private Sse() {}

    /** @return true if the handler stopped the stream, false if the stream ended first */
    static boolean read(InputStream in, Handler h) throws IOException, ModelException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String event = null;
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) {
                    if (!data.isEmpty() || event != null) {
                        if (!h.onEvent(event, data.toString())) {
                            return true;
                        }
                    }
                    event = null;
                    data.setLength(0);
                } else if (line.startsWith(":")) {
                    continue;
                } else if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.substring(5).startsWith(" ") ? line.substring(6) : line.substring(5));
                }
            }
            if (!data.isEmpty() || event != null) {
                return !h.onEvent(event, data.toString());
            }
            return false;
        }
    }
}
