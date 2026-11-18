package dev.jharness.sandbox;

/**
 * The outcome of one RunJS block.
 *
 * @param status {@code ok}, {@code error} (guest error), {@code limit} (a sandbox limit tripped),
 *     {@code cancelled} (wall-clock watchdog) or {@code failed} (the agent's isolate had to be stopped)
 * @param value the block's completion value as text (strings as is, other values as JSON), or null
 * @param error the error message, or null
 * @param line the 1-based line in the agent's code where the error happened, or 0 if unknown
 * @param limit the limit that tripped, e.g. {@code sandbox.MaxCPUTime}, or null
 * @param output what the code wrote to the console (stdout and stderr), capped by the stream limits
 */
public record RunResult(String status, String value, String error, int line, String limit, String output, long durationMs) {

    public boolean ok() {
        return status.equals("ok");
    }

    /** The text the agent loop sends back to the model. */
    public String forModel() {
        StringBuilder sb = new StringBuilder();
        if (!output.isEmpty()) {
            sb.append("Console output:\n").append(output);
            if (!output.endsWith("\n")) {
                sb.append('\n');
            }
        }
        if (ok()) {
            sb.append("Result: ").append(value == null ? "undefined" : value);
        } else {
            sb.append("Error").append(line > 0 ? " at line " + line : "").append(": ").append(error);
        }
        return sb.toString();
    }
}
