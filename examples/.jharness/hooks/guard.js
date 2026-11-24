// An example hook (spec 034). Copy it to ~/.jharness/hooks/ (yours) or a project's .jharness/hooks/.
// A hook file defines any of preTool(call), postTool(call) and stop(turn). It runs in its own sandbox
// before or after every api call of every agent; it can block a call, never approve one.

// Before a call: return {block: "reason"} to refuse it. call has agent, api, kind, summary and detail.
function preTool(call) {
  if (call.api === "git" && call.detail.op === "push" && /^(main|master)$/.test(call.detail.ref || "")) {
    return {block: "pushing to " + call.detail.ref + " is not allowed; push a branch and open a pull request"};
  }
  const path = call.detail.path || "";
  if ((call.api === "writeFile" || call.api === "editFile") && /(^|\/)(package-lock\.json|yarn\.lock|pnpm-lock\.yaml|gradle\.lockfile)$/.test(path)) {
    return {block: path + " is written by the build tool; change the dependency and run the tool instead"};
  }
  return null;
}

// When an agent would finish: return {continue: "message"} to make it go on (at most 3 times a turn).
// turn has agent, turn, steps, continued, reply and calls ([{api, kind, summary, status}]).
function stop(turn) {
  const changed = turn.calls.some(c => (c.api === "writeFile" || c.api === "editFile") && c.status === "ok");
  const tested = turn.calls.some(c => c.api === "tool" && / test\b/.test(c.summary));
  if (changed && !tested && turn.continued === 0 && turn.agent === "main") {
    return {continue: "You changed files but didn't run the tests. Run them (api.tools() shows which tool can), or say why you can't."};
  }
  return null;
}
