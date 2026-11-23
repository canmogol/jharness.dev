package dev.jharness.sandbox;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Runs {@code api.spawnAgent} requests (spec 026); the agent module implements it. */
@FunctionalInterface
public interface Subagents {

    /** Runs a subagent on the request's task and returns the result as a JSON string; throws for a bad request. */
    String spawn(ObjectNode request) throws Exception;
}
