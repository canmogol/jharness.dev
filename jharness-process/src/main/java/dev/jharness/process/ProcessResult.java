package dev.jharness.process;

/**
 * @param output the last {@code outputChars} characters of combined stdout and stderr (D-028)
 * @param truncated whether {@code output} was cut
 * @param full up to {@link ProcessRunner#FULL_OUTPUT_CHARS} characters from the start, for parsing and the session log
 * @param fullTruncated whether {@code full} was cut
 */
public record ProcessResult(int exitCode, String output, boolean truncated, String full, boolean fullTruncated,
        boolean timedOut, long durationMs) {
}
