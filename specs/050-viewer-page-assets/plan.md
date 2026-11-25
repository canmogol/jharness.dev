# Plan 050 — Viewer page assets

Spec: `spec.md` · Status: Done · Created: 2026-11-25

## Approach

- `index.html` links `app.css?t=__TOKEN__` and `app.js?t=__TOKEN__`; `SessionViewer` puts the viewer's token in when it serves `/`.
- Regression test first: `SessionViewerTest.thePagesOwnLinksLoad` fetches the page, resolves each `src` and `href` against the page's address and expects 200 (it failed with 403 before the fix).
- CI's native step fetches every link of the page as linked.
- D-083 note.

## Why the tests missed it

The unit test and CI fetched `/app.js?t=…` with the token added by hand, not the page's own links; nothing ran the page as a browser would.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | Same embedded files | Yes |
| II Host owns side effects | No change | Yes |
| III Untrusted | No change | Yes |
| IV No shell | No change | Yes |
| V Proxy | No change; loopback only | Yes |
| VI Strings | No change | Yes |
| VII Safe by default | Every request still needs the token | Yes |
| VIII Events | No change | Yes |
| IX Native first | CI's native step fetches the links | Yes |
| X Tested | Regression test first | Yes |
| XI Spec first | This spec | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [x] Native viewer checks on Linux (CI step)
- [ ] CI on all targets
