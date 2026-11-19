# Plan 008 — Loop notice for extra code blocks

Spec: `spec.md` · Status: Done · Created: 2026-11-19

## Approach

`JsBlocks` gains `find`, which walks every fenced block once and returns the first `js` block with a count of the later ones; `first` becomes a wrapper. `AgentLoop` uses `find`, puts the count on `loop.code`, and appends the note after `cap(...)`. No new event type, setting or dependency.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new process | — |
| II Host owns side effects | No new side effect; fewer blocks run, not more | — |
| III Untrusted | No change to policy, limits or context lifetime | — |
| IV No shell | No new process | — |
| V Proxy | No network | — |
| VI Strings | The note is a string in the result message | Yes |
| VII Safe by default | No change to approvals, trust or secrets | — |
| VIII Events | `loop.code` carries `ignoredBlocks` | Yes |
| IX Native first | No new dependency | — |
| X Tested | Unit, loop and replay regression tests | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Design

```java
public final class JsBlocks {
    public record Found(String code, int ignored) {}
    public static Optional<Found> find(String reply);          // same parsing as today, one pass
    public static Optional<String> first(String reply) {        // unchanged result
        return find(reply).map(Found::code);
    }
}
```

`AgentLoop` step:

```java
Optional<JsBlocks.Found> found = JsBlocks.find(last);
// loop.code: put("ignoredBlocks", n) only when n > 0
String message = cap(forModel(r));
if (n > 0) {
    message += "\n\nNote: your reply had " + n + " more js block(s) after the first; they did not run. "
            + "Send the next one in your next reply.";
}
conversation.add(ChatMessage.user(RESULT_PREFIX + message));
```

The interrupt path is unchanged: an interrupted step ends the turn, so no note is needed.

## Checks

- [x] `mvn -pl jharness-agent -am test -Dtest=JsBlocksTest -Dsurefire.failIfNoSpecifiedTests=false`
- [x] `mvn -pl jharness-agent,jharness-testkit -am test`
- [x] `mvn verify`
- [ ] P1 gate re-run by hand after merge (`docs/p1-gate.md`)

## Risks

| Risk | Response |
| --- | --- |
| A small model ignores the note and still claims the work | The gate re-run shows it; the result still says what ran |
| The note pushes the message past `timeouts.outputChars` | The note is short and fixed; only the result is capped |
