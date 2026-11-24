Review the changes in the working tree that aren't committed yet.

1. Call `changedFiles()` to see what changed, then `diffOf(path)` for each file worth reading.
2. Read the surrounding code with `api.readFile` or `api.grep` where the diff alone isn't enough.
3. Look for bugs, missing error handling, unclear names, missing tests and security problems.

Then reply without a code block. For each finding give the file and line, what is wrong and a suggested fix, most serious first. If you find nothing, say so. Don't comment on style that a formatter would fix.
