Look around a new project: list the source files and read the README:

```js
const files = JSON.parse(api.listFiles({glob: "*.java", max: 50})).files;
let readme = "(no README.md)";
try { readme = api.readFile("README.md").slice(0, 2000); } catch (e) {}
"Java files: " + files.join(", ") + "\n\nREADME:\n" + readme;
```

Find where a method is defined and read the file:

```js
const m = JSON.parse(api.grep({pattern: "int answer\\(", glob: "*.java"})).matches;
m.length ? api.readFile(m[0].path) : "not found";
```

Fix one line, then show the diff:

```js
api.editFile("src/App.java", "return 41;", "return 42;");
JSON.parse(api.git({op: "diff", paths: ["src/App.java"]})).output;
```

Find a tool that can run the tests, run them, and keep only the lines about failures:

```js
const tools = JSON.parse(api.tools()).filter(t => t.available && t.operations.test);
if (tools.length === 0) {
  "no tool here can run tests: " + JSON.parse(api.tools()).map(t => t.name + " (" + (t.reason || "ok") + ")").join(", ");
} else {
  const r = JSON.parse(api.tool(tools[0].name, {op: "test"}));
  r.exitCode === 0 ? tools[0].name + ": all tests pass"
    : r.output.split("\n").filter(l => /FAIL|ERROR|expected/.test(l)).slice(0, 30).join("\n");
}
```

Run one test class with Maven and report the exit code and the end of the output:

```js
const r = JSON.parse(api.tool("maven", {op: "test", tests: "AppTest"}));
"exit " + r.exitCode + (r.timedOut ? " (timed out)" : "") + "\n" + r.output.slice(-1500);
```

Check what changed before committing:

```js
const s = JSON.parse(api.git({op: "status"})).output.split("\n").filter(l => l && !l.startsWith("##"));
s.length === 0 ? "nothing to commit" : "changed files:\n" + s.join("\n");
```

Commit the change:

```js
api.git({op: "add", paths: ["src/App.java"]});
const c = JSON.parse(api.git({op: "commit", message: "Fix answer to return 42"}));
c.exitCode === 0 ? "committed" : c.output;
```

Read a documentation page and keep only the part you need:

```js
const p = JSON.parse(api.fetch("https://docs.example.com/guide/config"));
p.status !== 200 ? "status " + p.status
  : p.title + "\n" + p.text.split("\n").filter(l => /timeout/i.test(l)).slice(0, 20).join("\n");
```

Hand a side task to a background subagent, keep working, then collect its result:

```js
const a = JSON.parse(api.spawnAgent({task: "List every TODO comment in src/ with its file and line.", background: true})).agent;
const mine = JSON.parse(api.grep({pattern: "class App"})).matches.length;
const r = JSON.parse(api.waitAgent({agent: a, timeoutMs: 120000}));
"my search: " + mine + " match(es)\n" + a + " (" + r.status + "): " + (r.reply || "still running");
```

Record progress during a long task, so the user can follow it in the session log:

```js
const files = JSON.parse(api.listFiles({glob: "*.md"})).files;
api.emit({type: "agent.progress", step: "docs", files: files.length});
files.length + " Markdown files to check";
```

Handle a refused call without stopping the block:

```js
try {
  api.writeFile("notes.txt", "draft");
  "written";
} catch (e) {
  "could not write: " + e.message;
}
```
