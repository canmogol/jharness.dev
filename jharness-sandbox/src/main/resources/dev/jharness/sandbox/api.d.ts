/**
 * The JHarness api. Every method takes strings and returns a string; structured results are JSON
 * strings, so call JSON.parse on them. Object arguments are turned into JSON for you. A failed call
 * throws an Error whose message says why (for example "permission denied: ..." or "... is outside
 * the workspace"). Paths are relative to the workspace root.
 */

/** {"workspace": string, "os": string, "arch": string, "date": string,
 *   "git": {"branch": string, "dirty": boolean} | null, "files": string[]} — names ending in "/" are folders */
type ContextJson = string;

/** {"path": string, "bytes": number} */
type WriteResultJson = string;

/** {"path": string, "replacements": 1} */
type EditResultJson = string;

/** {"files": string[], "truncated": boolean} — workspace-relative paths, sorted */
type FilesJson = string;

/** {"matches": [{"path": string, "line": number, "text": string}], "truncated": boolean} */
type MatchesJson = string;

/** {"exitCode": number, "output": string, "truncated": boolean, "timedOut": boolean} — output is the last 30,000 characters */
type GitResultJson = string;

interface ListQuery {
  /** folder to list, default "." */
  path?: string;
  /** glob such as "*.java" or "src/**\/*.ts"; without a "/" it matches file names anywhere */
  glob?: string;
  /** most results, default 200, at most 5000 */
  max?: number;
}

interface GrepQuery {
  /** regular expression */
  pattern: string;
  /** folder or file to search, default "." */
  path?: string;
  glob?: string;
  ignoreCase?: boolean;
  max?: number;
}

/** Local git only; no fetch, pull, push or clone. */
type GitRequest =
  | { op: "status"; paths?: string[] }
  | { op: "diff"; staged?: boolean; stat?: boolean; rev?: string; paths?: string[] }
  | { op: "log"; max?: number; oneline?: boolean; rev?: string; paths?: string[] }
  | { op: "show"; rev: string; stat?: boolean }
  | { op: "branch" }
  | { op: "add"; paths: string[] }
  | { op: "commit"; message: string }
  | { op: "restore"; staged?: boolean; paths: string[] };

/** type must start with "agent." or "custom."; at most 64 KB */
interface AgentEvent {
  type: string;
  [field: string]: unknown;
}

declare const api: {
  /** Workspace root, OS, date, git branch and the top-level files. */
  readContext(): ContextJson;
  /** UTF-8 text of a file, up to 1 MB. */
  readFile(path: string): string;
  /** Creates or replaces a file; creates missing folders. Needs approval. */
  writeFile(path: string, content: string): WriteResultJson;
  /** Replaces oldText, which must occur exactly once, with newText. Needs approval. */
  editFile(path: string, oldText: string, newText: string): EditResultJson;
  /** Lists files, skipping hidden and git-ignored ones. */
  listFiles(query?: ListQuery | string): FilesJson;
  /** Searches file contents line by line. */
  grep(query: GrepQuery | string): MatchesJson;
  /** Runs a typed git operation. add, commit and restore need approval. */
  git(request: GitRequest | string): GitResultJson;
  /** Records an event in the session log; returns its id. */
  emit(event: AgentEvent | string): string;
};
