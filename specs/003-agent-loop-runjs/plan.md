# Plan 003 — Agent loop with RunJS

Spec: `spec.md` · Status: Draft · Created: 2026-11-18

## Approach

A new module `jharness-agent` (depends on `jharness-sandbox` and `jharness-llm`) holds the loop, block extraction, the system prompt and a `Session` that wires spec 002's parts together from settings. `api.d.ts` lives next to the shim in `jharness-sandbox`, and a contract test compares it with `HostApi`. The replay and recording providers go in `jharness-llm`, because they implement `ModelProvider`. The gate scenario and live tests go in `jharness-testkit`. The smoke binary gains a replay run of the loop.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new process or runtime | Yes |
| II Host owns side effects | The loop only calls the provider and `AgentIsolate.run`; every effect still goes through `api` and the gate | Yes |
| III Untrusted | Each step runs in a fresh context of the agent's isolate, with spec 002's limits; unchanged | Yes |
| IV No shell | No new process | — |
| V Proxy | Model calls use spec 002's providers and `HttpClients` (D-025 exception until P2); the replay provider makes no connection | Exception (D-025: proxy in P2) |
| VI Strings | Code goes in and the result comes out as strings | Yes |
| VII Safe by default | No change; sessions default to `DefaultGate` | Yes |
| VIII Events | `turn.*` and `loop.*` events for every turn and step | Yes |
| IX Native first | No new dependency; the smoke binary runs the loop with the replay provider | Yes |
| X Tested | The loop, error feedback, limits feedback and the gate scenario have tests | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Design

### Loop

```java
public final class AgentLoop {
    public TurnResult turn(String userMessage, Consumer<String> onDelta);
}
public record TurnResult(String reply, String stopReason, int steps) {}   // stopReason: done | max_steps | error
```

1. Emit `turn.started {prompt}`; append the user message.
2. Stream the reply with the system prompt and conversation; append it as an assistant message.
3. `JsBlocks.first(reply)`: the first fenced block whose info string is `js` or `javascript` (case-insensitive). None: emit `turn.completed {steps, stopReason: "done"}` and return.
4. Emit `loop.code {step, lines}`, run the block, emit `loop.result {step, status, limit?}`.
5. Append `RunResult.forModel()`, cut to `timeouts.outputChars` from the end, as the next user message, prefixed `Result of your code:`; go to 2.
6. After `agent.maxSteps` steps stop with `max_steps`; on `ModelException` stop with `error`.

### Settings

New section `agent`: `{"maxSteps": 25}` (1 to 200).

### System prompt

A resource `system-prompt.md` with three slots: `{{API}}` (the text of `api.d.ts`), `{{EXAMPLES}}` (`examples.md`) and `{{CONTEXT}}` (the `readContext` JSON at session start). Rules in the prompt: act only by writing one fenced `js` block per reply; every `api` call takes and returns strings, so `JSON.parse` structured results; the block's last expression is the result; errors come back with line numbers; reply without code when the task is done, with a short summary.

### `api.d.ts`

Declares `api` with the eight methods and the JSON shapes from spec 002's plan as TypeScript interfaces in comments. `ApiContractTest` parses the method names and arities from `api.d.ts` and compares them with `HostApi`'s `@HostAccess.Export` methods and the shim.

### Replay and recording

- `ReplayProvider(Path jsonl)` or `ReplayProvider(List<String>)`: one line per reply, `{"reply":"…"}` or `{"error":"…"}`. Each reply is streamed as one or more `model.delta` events, then `model.completed` with stop reason `replay`. When the recording runs out it throws `ModelException("replay recording has no more replies (n used)")`.
- `RecordingProvider(ModelProvider, Path)`: passes calls through and appends each reply or error to the file.

### Session

`Session.open(home, workspace, settings, profileName, gate)` builds the bus, session log, workspace, process runner, git, search, isolate manager, the main agent's isolate, provider and loop. `Session.withProvider(...)` swaps the provider for tests. `close()` closes the isolate and the log.

### Events

| Type | Fields |
| --- | --- |
| `turn.started` | `prompt` |
| `turn.completed` | `steps`, `stopReason` |
| `loop.code` | `step`, `lines` |
| `loop.result` | `step`, `status`, `limit` |

## Checks

- [ ] `mvn -B -ntp verify`
- [ ] `mvn -B -ntp -Pnative -pl jharness-testkit package -DskipTests && jharness-testkit/target/jharness-smoke`

## Risks

| Risk | Response |
| --- | --- |
| Small local models ignore the reply rules | Examples in the prompt; errors with line numbers; the live tests show how well a 7B model does, recorded in the PR |
| A reply holds several blocks | Only the first runs; the prompt says one block per reply |
| Long results fill the context | Capped at `timeouts.outputChars`; compaction comes in P4 |
