package dev.jharness.security;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * An {@code api} call waiting for a permission decision.
 *
 * @param api the method, e.g. {@code writeFile} or {@code git}
 * @param kind {@link Kind}
 * @param summary one line for a person, e.g. {@code write src/App.java (1,204 bytes)}
 * @param detail structured detail for events and later for approval prompts
 */
public record Operation(String api, Kind kind, String summary, ObjectNode detail) {

    public enum Kind {
        READ, WRITE, GIT_READ, GIT_WRITE, EMIT,
        /** A Maven, Gradle or npm run (spec 013): it runs the project's own build code. */
        BUILD,
        /** A connection through the egress proxy that no rule decided (spec 011); asked by the proxy itself. */
        NETWORK;

        /** True for kinds that change state and need approval by default (D-010). */
        public boolean changesState() {
            return this == WRITE || this == GIT_WRITE || this == BUILD;
        }

        public String wire() {
            return name().toLowerCase().replace('_', '-');
        }
    }
}
