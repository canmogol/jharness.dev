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

Commit the change:

```js
api.git({op: "add", paths: ["src/App.java"]});
const c = JSON.parse(api.git({op: "commit", message: "Fix answer to return 42"}));
c.exitCode === 0 ? "committed" : c.output;
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
