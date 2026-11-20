package dev.jharness.process;

import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** The {@code timeoutMs} field of a git or build request (D-028): the default when absent, never above the maximum. */
final class CallTimeout {

    static final String FIELD = "timeoutMs";

    private CallTimeout() {}

    static Duration of(ObjectNode req, Duration dflt, Duration max) {
        JsonNode n = req.get(FIELD);
        if (n == null || n.isNull()) {
            return dflt.compareTo(max) > 0 ? max : dflt;
        }
        if (!n.isIntegralNumber() || n.asLong() < 1 || n.asLong() > max.toMillis()) {
            throw new OperationException("\"" + FIELD + "\" must be a whole number of milliseconds from 1 to " + max.toMillis()
                    + " (timeouts.processMax)");
        }
        return Duration.ofMillis(n.asLong());
    }
}
