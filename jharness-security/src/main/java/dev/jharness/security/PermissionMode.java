package dev.jharness.security;

/** D-041. */
public enum PermissionMode {
    ASK, ACCEPT_EDITS, PLAN, BYPASS;

    /** From the settings value: {@code ask}, {@code accept-edits}, {@code plan} or {@code bypass}. */
    public static PermissionMode of(String setting) {
        return valueOf(setting.toUpperCase().replace('-', '_'));
    }
}
