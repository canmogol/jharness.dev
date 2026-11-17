# Security policy

JHarness runs code written by a language model. Sandbox escapes, permission bypasses and secret leaks are the bugs we care about most.

## Supported versions

| Version | Supported |
| --- | --- |
| `master` | Yes |
| Released versions | None yet (pre-v0.1) |

## Reporting a vulnerability

1. Don't open a public issue, pull request or discussion.
2. Report it privately through GitHub: **Security → Report a vulnerability** on this repository.
3. Include the version or commit, the OS and architecture, the steps to reproduce, and what an attacker gains.

## What happens next

| Step | Target |
| --- | --- |
| Acknowledge the report | 3 working days |
| First assessment (confirmed or not, severity) | 10 working days |
| Fix or mitigation for a confirmed issue | 90 days, sooner for critical issues |
| Public advisory | After the fix ships, crediting the reporter unless they ask not to be named |

Every confirmed security bug gets a regression test before it is fixed (constitution Article X).

## In scope

- Agent JavaScript escaping the GraalJS isolate, or reaching host objects other than the `api` facade.
- Getting around isolate limits (memory, CPU, threads, wall clock) or the permission checks.
- Starting a process outside `ProcessRunner`, or injecting arguments into one.
- Network traffic that skips the host egress proxy or its rules.
- Secrets reaching prompts, the guest or the session log.
- A repository running its own code or settings before the user trusts the project.

## Out of scope

- Anything that needs the user to approve the action, set bypass mode or change limits on purpose.
- Bugs in GraalVM, model providers or build tools themselves; report those upstream. Tell us if JHarness makes them exploitable.
- Code under `spike/`, which is throwaway measurement code. Reports are still welcome.
