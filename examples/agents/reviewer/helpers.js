// Helpers for the reviewer agent. JHarness defines them before every block.

/** Paths with uncommitted changes, staged or not, from `git status --porcelain`. */
function changedFiles() {
  const out = JSON.parse(api.git({op: "status"})).output;
  return out.split("\n")
    .filter(l => l.length > 3 && !l.startsWith("##"))
    .map(l => l.slice(3).split(" -> ").pop());
}

/** The diff of one path against HEAD, staged and unstaged together. */
function diffOf(path) {
  return JSON.parse(api.git({op: "diff", rev: "HEAD", paths: [path]})).output;
}
