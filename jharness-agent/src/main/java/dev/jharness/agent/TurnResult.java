package dev.jharness.agent;

/**
 * @param reply the model's last reply
 * @param stopReason {@code done} (a reply without code), {@code max_steps}, {@code error} or {@code interrupted}
 * @param steps RunJS steps taken in the turn
 */
public record TurnResult(String reply, String stopReason, int steps, String error) {
}
