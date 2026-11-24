package dev.jharness.sandbox;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JS hooks around every {@code api} call (spec 034, D-022, D-073); the agent module implements it. Hooks
 * only tighten: a pre-tool hook can block a call before the permission gate sees it, never approve one.
 */
public interface Hooks {

    /** Before the gate: null to go on, or why a hook blocked the call. */
    String preTool(String agent, String api, String kind, String summary, ObjectNode detail);

    /** After the call ran, failed or was denied; hooks observe only. */
    void postTool(String agent, String api, String kind, String summary, String status, String result, String error);
}
