# Plan 052 — Viewer feedback

Spec: `spec.md` · Status: Done · Created: 2026-11-25

## Approach

- `EventFeed.attach` and `detach` become public, so the command can follow the same feed as the browsers.
- `ViewCommand` attaches a listener that puts each line on a queue; a daemon thread takes them, formats each with `LogFormat` (the `jharness log SESSION` format) and prints it. Ctrl-C interrupts it.
- `--quiet` / `-q` skips the printer.
- `index.html` links `data:,` as its icon; the CSP's `img-src` adds `data:`. The page sets event text only as text, so the wider `img-src` adds no way in.
- `markdown.js`, a new embedded file loaded before `app.js` (with the token, served like the others, in the native image's resources): a small block and inline Markdown renderer that builds nodes with `createElement`, `createTextNode` and `textContent`. Links only for `http(s)`. A reply's body keeps its raw text and is re-rendered at most once a frame while chunks stream.
- `ViewerMarkdownTest` runs `markdown.js` in GraalJS (an isolate engine) with a small fake DOM and checks the nodes; the sink test also covers `markdown.js` and adds `setAttribute` and `srcdoc`.
- Tests: `ViewTest` checks the printed events after the address and `--quiet`; `SessionViewerTest.thePagesOwnLinksLoad` checks the icon and the CSP.
- CI's native step skips `data:` links when fetching, expects three linked files, checks the icon and greps the printed `session.started` and `turn.completed`.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | Same binary and embedded files | Yes |
| II Host owns side effects | Prints to the terminal only | Yes |
| III Untrusted | Event text goes through `LogFormat`, which escapes control characters; in the page, Markdown becomes nodes and text, never markup, and links are `http(s)` only | Yes |
| IV No shell | No process | Yes |
| V Proxy | No network change; loopback only | Yes |
| VI Strings | No change | Yes |
| VII Safe by default | Every request still needs the token | Yes |
| VIII Events | No new action | Yes |
| IX Native first | CI's native step checks the output | Yes |
| X Tested | `ViewTest`, `SessionViewerTest` | Yes |
| XI Spec first | This spec | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [x] Native viewer checks on Linux (CI step)
- [ ] CI on all targets
