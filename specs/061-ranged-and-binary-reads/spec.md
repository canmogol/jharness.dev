# Spec 061 — Ranged and binary reads

Status: Done · Phase: P3 · Created: 2026-11-26

## Summary

A local model couldn't read a 12 MB file: `api.readFile` reads whole UTF-8 files up to 1 MB. Now `api.readFile(path, {fromLine, lines})` or `{offset, length}` reads a part of a text file of any size, and a new `api.readBytes(path, {offset, length, encoding})` reads any file, binary too, as base64, hex or text in a charset. One call returns at most 1 MB. Decision D-094.

## Roadmap items

- Ranged and binary reads (user report, 2026-11-26)

## User stories

| ID | Story | From |
| --- | --- | --- |
| — | The agent can read a large file in parts, from a given byte, N bytes at a time | User report, 2026-11-26 |
| — | The agent can read files in other charsets, and binary files | User requests, 2026-11-26 |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | `api.readFile(path)` is unchanged. A file over 1 MB is refused with a message that names `{fromLine, lines}`, `{offset, length}` and `api.readBytes`; a file that isn't UTF-8 names `api.readBytes`. |
| AC-02 | `api.readFile(path, {fromLine, lines})` returns whole lines from `fromLine` (1-based), up to `lines` (at most 100,000) and at most 1 MB; past the end it returns "". A single line over 1 MB is an error. `api.readFile(path, {offset, length})` returns the UTF-8 text of that byte range (at most 1 MB), cut to whole characters at both ends. Lines and bytes together are refused. |
| AC-03 | `api.readBytes(path, {offset, length, encoding})` returns `{path, size, offset, length, nextOffset, eof, encoding, content}`: by default 1 MB from byte 0, as base64; `"hex"`; or any charset name Java knows, decoded strictly, with a character cut at the end of the range left for the next call. An unknown charset or invalid bytes are errors that name the alternatives. `-H:+AddAllCharsets` puts every charset in the native binary. |
| AC-04 | Both follow the read rules: workspace and mounts, never the deny list or outside; folders refused; unknown options and out-of-range numbers refused. |
| AC-05 | `api.d.ts`, the JS shim, the reachability metadata, custom agents' `tools` and the system prompt name them. Tests for each case; CI's native step decodes windows-1252 and hex. README, D-094, Q103, roadmap. |

## Out of scope

- Writing binary files.
- Reading backwards from the end (tail); `{fromLine}` after a line count from `api.grep` covers most cases.

## Dependencies and assumptions

- Specs 002 and 004 (the file api and policy).

## Open questions

None. Design confirmed by the user on 2026-11-26: a separate `readBytes`, base64 with hex optional, byte and line ranges, 1 MB per call, strict decoding.
