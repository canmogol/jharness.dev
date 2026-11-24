package dev.jharness.sandbox;

/** {@code api.todos} and {@code api.proposePlan} (spec 036, D-075); the agent module implements it. */
public interface Planning {

    /** Sets the agent's todo list when {@code json} is a list, and returns the list as JSON. */
    String todos(String agent, String json);

    /** In plan mode: shows the plan and asks the user whether to leave plan mode and carry it out. */
    String proposePlan(String agent, String plan);
}
