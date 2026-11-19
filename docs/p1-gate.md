# P1 gate run

Gate (`../product/roadmap.md`): fixes a seeded bug in a sample repo end to end, with a local model and with Claude. The Claude run moves to the v0.1 release checks (D-045); this run covers the local model.

Status: **passed** on attempt 2 (local model) · Run: 2026-11-19

## Summary

| Check | Pass if | Local model | Claude |
| --- | --- | --- | --- |
| Finds the bug | Searches or lists, then reads `Calculator.java` | Pass | Deferred (D-045) |
| Fixes it | `add` returns `a + b`; nothing else changed | Pass | Deferred (D-045) |
| Approvals | Asked for the edit and the commit in `ask` mode | Pass | Deferred (D-045) |
| Commits | A new commit after `Initial commit` | Pass | Deferred (D-045) |
| Ends the turn | Stop reason `done`, within 15 steps | Pass | Deferred (D-045) |
| Session log | One JSONL file with the turn's events | Pass | Deferred (D-045) |

Covered without a live Claude run: the Claude provider against the stub server (spec 002) and the gate scenario through the replay provider (spec 003, `GateScenarioTest`).

## Setup

| Item | Attempt 1 | Attempt 2 |
| --- | --- | --- |
| Machine | Apple M4, macOS 26.7.3 | A second machine of the user's |
| Binary | `jharness 0.1.0-SNAPSHOT`, native, before spec 008 | Built from `master` after specs 008–010 |
| Model server | LM Studio, OpenAI-compatible API on the local network | LM Studio, OpenAI-compatible API |
| Model | `qwen2.5-coder-7b-instruct` | `qwen/qwen3.6-27b` (reasoning model) |

1. Build: `mvn install`, then `mvn -Pnative -pl jharness-cli package -DskipTests` for the native binary.
2. Put the model profile in `~/.jharness/settings.json`, not in a project folder: the sample repo is a new, untrusted folder, so its own settings can't add a profile.
3. Create the sample repo (the same files as `SampleRepo` in `jharness-testkit`):

   ```
   mkdir -p /tmp/p1-gate/src/main/java/demo /tmp/p1-gate/src/test/java/demo && cd /tmp/p1-gate
   cat > src/main/java/demo/Calculator.java <<'EOF'
   package demo;

   /** Basic arithmetic. */
   public final class Calculator {

       public int add(int a, int b) {
           return a - b;
       }

       public int multiply(int a, int b) {
           return a * b;
       }
   }
   EOF
   cat > src/test/java/demo/CalculatorTest.java <<'EOF'
   package demo;

   class CalculatorTest {
       void add() {
           assert new Calculator().add(2, 3) == 5;
       }
   }
   EOF
   printf '# demo\n\nA calculator.\n' > README.md
   git init -q -b main && git add . && git commit -q -m "Initial commit"
   ```

## Run

1. In `/tmp/p1-gate`, start `jharness` (or `jharness --model <profile>`). Answer the trust question with no.
2. Send the task: `Calculator.add returns the wrong result: add(2, 3) should be 5. Find the bug, fix it, and commit the fix with a short message.`
3. Approve the edit and the commit with `y`.
4. After the turn, check:

   ```
   git log --oneline          # a commit above "Initial commit"
   git show --stat HEAD       # only Calculator.java changed
   grep -n "return a" src/main/java/demo/Calculator.java
   ls -t ~/.jharness/sessions/*/ | head -1
   ```

5. Fill in the summary and the notes below. Up to three attempts; record each one.

## Attempts

| # | Steps | Stop reason | Fixed | Committed | Notes |
| --- | --- | --- | --- | --- | --- |
| 1 | 11 + 1 | `done`, then `done` | Yes | Only after a second prompt | See attempt 1 below |
| 2 | Not recorded | `done` | Yes | Yes, in the same turn | See attempt 2 below |

## Notes

### Attempt 1

- Steps 1–9 wandered: four greps, a `listFiles` for `*.js` and `*.ts`, `readContext` again and `README.md`. Step 10 read `Calculator.java`.
- Step 11's reply had two `js` blocks: the edit, then `git add` and `commit`. The loop runs only the first block (spec 003 AC-01), so the edit ran (approved, diff shown, checkpoint saved) and the commit block was dropped without a word to the model.
- The next reply said the fix was committed and ended the turn (`done`). It wasn't.
- After the user wrote "You didn't commit the change", turn 2 ran `git add` and `commit` in one step, both approved. Result: `b93b838 Fix Calculator.add to return the correct sum`, only `Calculator.java` changed.
- Checks: found yes (slowly), fixed yes, approvals yes, ended `done` within 15 steps yes, session log yes. Committed in the same turn: no.
- Finding: when a reply has more than one block, the loop should tell the model that the later blocks didn't run, so it doesn't report work it never did.

### Attempt 2

- Run by the user on a second machine with `qwen/qwen3.6-27b`, following this procedure, after specs 008 (notice for extra code blocks), 009 (TUI usability) and 010 (thinking text, quieter log).
- Reported result: every check passed, with the fix committed in the same turn.
- The session log stayed on that machine, so step counts and timings are not recorded here.
