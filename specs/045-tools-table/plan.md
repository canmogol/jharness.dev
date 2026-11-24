# Plan 045 — Tools table

Spec: `spec.md` · Status: Done · Created: 2026-11-24

## Approach

- `TextTable` (cli): a header and rows, escaped first, column widths from the longest value, two spaces apart, last column unpadded, trailing spaces stripped.
- `ToolsCommand` list: a `TextTable` with one row per tool and per broken file; a "Differs from the shipped default" line and one line per broken file after it.
- Tests: `ToolsFlowTest.textTableColumnsAreAsWideAsTheirLongestValue` (exact lines, an escaped value), and `toolsCommand` checks the header and that each row's columns start where the header's do, the `broken` row, and the notes.
- D-066 and Q89, README.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new dependency | Yes |
| II Host owns side effects | Output only | Yes |
| III Untrusted | Isolates unchanged | Yes |
| IV No shell | No process starts; PATH lookup as before | Yes |
| V Proxy | No network use | Yes |
| VI Strings | Nothing crosses the isolate boundary | Yes |
| VII Safe by default | Values escaped as before | Yes |
| VIII Events | No new action | Yes |
| IX Native first | Plain Java | Yes |
| X Tested | Header, alignment, escaping, notes | Yes |
| XI Spec first | This spec | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| Wide characters in a name misalign columns | Widths count code points; tool names are ASCII by the file name pattern |
