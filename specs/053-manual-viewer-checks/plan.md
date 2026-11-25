# Plan 053 — Manual viewer and terminal checks

Spec: `spec.md` · Status: Done · Created: 2026-11-25

## Approach

- `scripts/viewer-check.mjs` (Node, no dependencies of its own): finds Playwright in the current folder's, the repo's or the global `node_modules`.
  - A headless replay run (`-p`) records the session; `jharness view` serves it; the address comes from its output.
  - Playwright: an init script records `securitypolicyviolation` events; listeners record console errors, page errors, failed requests and every response's status. After "live" and the table, it waits 1.5 s for late requests such as a favicon.
  - Safari: `safaridriver -p PORT` and plain WebDriver calls through `fetch` (new session, navigate, execute script, screenshot).
  - The facts come from one function run in the page, so both paths check the same things.
- `scripts/tui-tmux-check.py`: its own tmux server (`-L`, no config file), one session per size, `remain-on-exit` to read the exit status, `capture-pane -J` to wait for text across wrapped lines, `-e` for styles.
- Why Node for the browser script: Playwright's Node package needs only npm; Python's needs pip and a virtual environment, which a machine may lack.
- Docs: `docs/manual-checks.md` (setup, run, a results table), README paragraph, D-086, Q95, D-083 note, roadmap item and gate.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No change to the binary; scripts are dev tools | Yes |
| II Host owns side effects | No change | Yes |
| III Untrusted | No change | Yes |
| IV No shell | The binary starts nothing new; the scripts are outside it | Yes |
| V Proxy | No change; the browsers reach `127.0.0.1` only | Yes |
| VI Strings | No change | Yes |
| VII Safe by default | No change | Yes |
| VIII Events | No change | Yes |
| IX Native first | The checks run the native binary on Linux and macOS | Yes |
| X Tested | The checks are tests, run by hand (D-086); CI keeps its own | Yes |
| XI Spec first | This spec | Yes |

## Checks

- [x] `viewer-check.mjs` on macOS: Chromium, Firefox, WebKit
- [ ] `viewer-check.mjs` on Linux: needs the browsers' system libraries (`playwright install-deps`, sudo)
- [ ] `viewer-check.mjs` with Safari: needs Allow Remote Automation on the macOS machine
- [ ] `viewer-check.mjs` with WebKit on macOS 14: Playwright's frozen build crashes; needs macOS 15 or later
- [x] `tui-tmux-check.py` on Linux and macOS (it finds a resize bug; see the PR)
- [x] `mvn install` on Linux
