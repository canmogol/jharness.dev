// An example JS slash command (spec 035): /changed prints the changed files without asking the model.
const description = "list the files git sees as changed";

function run(args) {
  const lines = JSON.parse(api.git({op: "status"})).output.split("\n").filter(l => l && !l.startsWith("##"));
  return {print: lines.length ? lines.join("\n") : "No changes."};
}
