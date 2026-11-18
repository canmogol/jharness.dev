# P1 gate run

Gate (`../product/roadmap.md`): fixes a seeded bug in a sample repo end to end, with a local model and with Claude. The Claude run moves to the v0.1 release checks (D-045); this run covers the local model.

Status: **pending** · Run: <YYYY-MM-DD>

## Summary

| Check | Pass if | Local model | Claude |
| --- | --- | --- | --- |
| Finds the bug | Searches or lists, then reads `Calculator.java` | | Deferred (D-045) |
| Fixes it | `add` returns `a + b`; nothing else changed | | Deferred (D-045) |
| Approvals | Asked for the edit and the commit in `ask` mode | | Deferred (D-045) |
| Commits | A new commit after `Initial commit` | | Deferred (D-045) |
| Ends the turn | Stop reason `done`, within 15 steps | | Deferred (D-045) |
| Session log | One JSONL file with the turn's events | | Deferred (D-045) |

Covered without a live Claude run: the Claude provider against the stub server (spec 002) and the gate scenario through the replay provider (spec 003, `GateScenarioTest`).

## Setup

| Item | Value |
| --- | --- |
| Machine | |
| Binary | `jharness --version` output; JVM or native |
| Model server | e.g. LM Studio, address |
| Model | e.g. `qwen2.5-coder-7b-instruct`, quantization |

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
| 1 | | | | | |

## Notes

- 
