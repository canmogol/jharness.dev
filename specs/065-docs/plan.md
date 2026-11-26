# Plan 065 — User guide, api reference and extension guide

Spec: `spec.md` · Status: Done · Created: 2026-11-26

## Approach

- **`docs/api.md`.**
  - `ApiReference` (jharness-sandbox tests) reads `api.d.ts`: the file comment, the top-level `type` and `interface` declarations with their comments, and the members of `declare const api`.
  - It writes the overview, a method table, a section per method and the types, as declared, in `ts` blocks.
  - `ApiReferenceTest` compares the result with `docs/api.md`; with `-Djharness.writeApiReference=true` it writes the file instead. `scripts/api-reference.sh` runs that.
- **`docs/user-guide.md`.**
  - The README's "Build and run" text moves here, ordered by task, with a contents list.
  - New sections on permissions and trust, and a settings reference table.
- **`docs/extensions.md`.** One section per extension, built on the files in `examples/`, with the tool definition keys and field types as tables.
- **Examples.**
  - `examples/settings.json`: `SettingsTest` parses it.
  - `examples/statusline.js`: `StatusLineTest` runs it.
- **`DocsTest`** (jharness-cli tests): the links and `examples/` paths exist, and the settings keys are named. `Settings.KEYS` becomes public for it.
- **README.** It links to the three documents; Install stays; "Build and run" keeps only the build from source.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new dependency; the docs aren't shipped | Yes |
| II Host owns side effects | No runtime change | Yes |
| III Untrusted | No isolate change | Yes |
| IV No shell | `scripts/api-reference.sh` runs Maven at build time only | Yes |
| V Proxy | Unchanged | Yes |
| VI Strings | Unchanged | Yes |
| VII Safe by default | The examples keep secrets out of settings (`apiKeyEnv`, `tokenSecret`) | Yes |
| VIII Events | No new action | Yes |
| IX Native first | No code in the binary changes but a visibility | Yes |
| X Tested | Generated reference, examples, links and keys are tested | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| The guides go stale as features change | Tests catch missing keys, links and examples; `api.md` is generated |
| The user guide repeats the README | The README keeps only the overview, install and build, and links |
