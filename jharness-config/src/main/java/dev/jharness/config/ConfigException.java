package dev.jharness.config;

/** Settings are invalid; the message names the file and key. Start-up stops (D-003). */
public final class ConfigException extends Exception {
    public ConfigException(String message) {
        super(message);
    }
}
