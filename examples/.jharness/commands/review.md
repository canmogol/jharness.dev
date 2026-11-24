---
description: review the uncommitted changes, optionally only in the given paths
---
Review the uncommitted changes in this project $ARGUMENTS.

Use api.git({op: "diff"}) and api.git({op: "status"}) to see them, and read the changed files where you need more context.
List concrete problems first (bugs, missing tests, risky changes), each with the file and line, then smaller remarks.
Don't change any files.
