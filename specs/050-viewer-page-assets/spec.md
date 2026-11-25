# Spec 050 — Viewer page assets

Status: Done · Phase: P4 · Created: 2026-11-25

## Summary

Bug fix for spec 048. The viewer's page linked `app.js` and `app.css` without the token, so a browser got 403 for both: the page showed unstyled, stayed at "connecting…" and listed no events. The server now puts the token into the page's two links. Decision D-083 (note).

## Roadmap items

None (a fix to spec 048's item).

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-3.6 | As a security-minded user, I want a log of everything the agent did, so I can audit a session. | `user-stories.md` |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | The page served at `/?t=TOKEN` links `app.js?t=TOKEN` and `app.css?t=TOKEN`; each loads (200) when fetched exactly as linked. A regression test resolves the page's links like a browser. |
| AC-02 | Every request still needs the token; nothing else in the checks changes. |
| AC-03 | CI's native step fetches each link of the page as linked; D-083 notes the fix. |

## Out of scope

- Serving the files without the token.
- A browser in CI.

## Dependencies and assumptions

- Spec 048, D-083. The token is base64url, so it needs no escaping in an attribute.

## Open questions

None.
