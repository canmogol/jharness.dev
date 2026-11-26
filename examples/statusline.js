// An example status line script (spec 042). Copy it to ~/.jharness/statusline.js.
// status(s) returns the line; s.line is the default one. It runs in its own sandbox with no api.
function status(s) {
  const parts = [s.model, s.context.percent + "%" + (s.context.estimated ? "~" : "")];
  if (s.plan) {
    parts.push("PLAN");
  }
  if (s.agents.running + s.agents.held > 0) {
    parts.push(s.agents.running + " running" + (s.agents.held ? ", " + s.agents.held + " waiting for you" : ""));
  }
  if (s.costUsd !== null && s.costUsd !== undefined) {
    parts.push("$" + s.costUsd.toFixed(2));
  }
  parts.push(s.busy ? "working" : "turn " + s.turn);
  return parts.join(" | ");
}
