package dev.jharness.sandbox;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Runs {@code api.spawnAgent} requests and answers {@code api.models} (spec 026); the agent module implements it. */
public interface Subagents {

    /** Runs a subagent on the request's task and returns the result as a JSON string; throws for a bad request. */
    String spawn(ObjectNode request) throws Exception;

    /** The model profiles a subagent may run with, as a JSON string, for {@code api.models}. */
    String models();

    /** {@code api.agents}: this session's subagents as a JSON array. */
    String agents();

    /** {@code api.stopAgent}: stops a running background subagent. */
    String stopAgent(String name) throws Exception;
}
