# Spec 052 — Viewer feedback

Status: Done · Phase: P4 · Created: 2026-11-25

## Summary

User feedback on spec 048. `jharness view` printed only the address, so the terminal it ran in stayed silent while the page and `tail -f` showed the session. It now prints each event as `jharness log SESSION` does: the log so far, then each new event, until Ctrl-C. `--quiet` prints only the address. The page also shows replies as Markdown (bold, code blocks, lists, tables, links), as the terminal already does, instead of raw `**` and fences; and it declares an empty inline icon, so browsers stop asking for `/favicon.ico` without the token and logging a 403. Decision D-083 (note).

## Roadmap items

None (a change to spec 048's item).

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-3.6 | As a security-minded user, I want a log of everything the agent did, so I can audit a session. | `user-stories.md` |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | After the address, `jharness view` prints each event of the log in `jharness log SESSION`'s format, then each new one as the log grows. |
| AC-02 | Printing never holds up the log tail or the browsers: the feed's listener only queues a line. |
| AC-03 | `--quiet` (`-q`) prints only the two address lines; the browser still gets every event. |
| AC-04 | The page has `<link rel="icon" href="data:,">` and the CSP allows `data:` images, so a browser makes no `/favicon.ico` request. Every request still needs the token. |
| AC-05 | The page renders replies as Markdown, streamed ones too: fenced code, headings, lists (nested and numbered), quotes, tables, rules, inline code, bold, italics, strikethrough and links. |
| AC-06 | The renderer builds DOM nodes and sets text only as text; links get only `http` and `https` addresses, with `rel="noopener noreferrer"`. A test runs it on markup and `javascript:` links; the sink test covers it. |
| AC-07 | Tests cover AC-01 and AC-03 to AC-06; CI's native step checks the printed events, the icon and the page's three links; D-083, the tech stack and the README note it. |

## Out of scope

- Filters (`--type`, `--agent`) for the printed events; `jharness log` has them.
- Streamed reply chunks in the terminal (the log has none).
- Serving `/favicon.ico` without the token.
- Markdown in thinking, prompts or steps; raw HTML in replies (shown as text); syntax highlighting.

## Dependencies and assumptions

- Specs 021 (`LogFormat`), 048 and 050; D-083.

## Open questions

None.
