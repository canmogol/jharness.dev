# Spec 053 — Manual viewer and terminal checks

Status: Done · Phase: P4 · Created: 2026-11-25

## Summary

Two scripts check the web viewer and the TUI as a person sees them, by hand and never in CI. `scripts/viewer-check.mjs` opens `jharness view` in Chromium, Firefox and WebKit with Playwright, and in Safari through `safaridriver`. It checks the page's files, the console, refused requests and the Markdown, and that the terminal printed the events. `scripts/tui-tmux-check.py` runs the TUI in tmux at several sizes, with a resize. Both run after viewer or terminal UI changes and before each release, on Linux and macOS. Decision D-086.

## Roadmap items

- Manual browser and terminal-size checks of the viewer and TUI (added and ticked; the v0.1 gate names them).

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-3.6 | As a security-minded user, I want a log of everything the agent did, so I can audit a session. | `user-stories.md` |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | `node scripts/viewer-check.mjs BIN [--browsers …] [--out DIR]` records a replay session whose reply has bold, a code block, a list, a table and a link, and starts `jharness view` on it. |
| AC-02 | For each Playwright browser (chromium, firefox, webkit) it waits for "live", then fails on: `app.css`, `app.js` or `markdown.js` not loaded; a console error, page error or CSP violation; a response of 400 or more (the favicon included) or a failed request; a missing `<strong>`, `<pre>`, list items, `<table>` or `https` `<a>`. It saves a full-page screenshot. |
| AC-03 | `--browsers safari` drives Safari through `safaridriver`'s WebDriver endpoint with the same page and Markdown checks and the CSP violations the page saw; when remote automation is off it says how to turn it on. |
| AC-04 | It fails if `jharness view` didn't print `session.started`, `turn.started`, `model.completed` and `turn.completed`. It exits 0 when all pass, 1 on a failure, 2 when Playwright is missing (with install commands) or for bad arguments. |
| AC-05 | `scripts/tui-tmux-check.py BIN [--sizes …]` runs the TUI in tmux per size: trust answer, prompt, reply styles in `capture-pane -e` (bold, coloured code, no raw `**`), the status line on the last row and only once, again after a larger and a smaller resize, and exit 0 on Ctrl-D. It saves each screen with escape codes and as text; exits 2 without tmux. |
| AC-06 | Neither script, Playwright nor tmux is in CI or the Maven build. |
| AC-07 | `docs/manual-checks.md` and the README say how to set them up and when to run them (viewer or TUI changes, each release); the roadmap's v0.1 gate names them; D-086, Q95 and a D-083 note. |

## Out of scope

- Browsers or tmux in CI.
- Fixing what the checks find (each finding gets its own spec).
- Windows terminals and browsers.

## Dependencies and assumptions

- Specs 048 to 052 (the viewer, its page fix and Markdown), 005 (`tui-pty-check.py`), D-083.
- Node and npm on each machine; Playwright installed globally; tmux.

## Open questions

None.
