# Tasks 059 — Go tool

Spec: `spec.md` · Plan: `plan.md`

- [x] T001 `go.json` and `gofmt.json`; shipped defaults (AC-01, AC-04, AC-05)
- [x] T002 Home paths in `env` values; `ToolDefinitionTest` (AC-05)
- [x] T003 `BuildOperationsTest`: argv, refused values, environment, offline (AC-01, AC-02, AC-04, AC-05)
- [x] T004 `BuildToolsTest` with real Go: operations, toolchain, refused host, vendor offline, gofmt (AC-01, AC-02, AC-04, AC-05)
- [x] T005 `examples/network.rules` (AC-03)
- [x] T006 CI setup-go and the native step; native checks on Linux and macOS (AC-06)
- [x] T007 README, D-092, Q101, roadmap (AC-07)

## Traceability

| AC | Tasks |
| --- | --- |
| AC-01 | T001, T003, T004 |
| AC-02 | T003, T004 |
| AC-03 | T005 |
| AC-04 | T001, T003, T004 |
| AC-05 | T001, T002, T003, T004 |
| AC-06 | T006 |
| AC-07 | T007 |
