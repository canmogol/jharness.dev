"use strict";

// Splits one line of a CSV export into its fields.
function parseLine(line) {
  return line.split(",");
}

// Parses a whole export: the first line is the header; returns one object per row.
function parse(text) {
  const lines = text.split("\n").filter((l) => l.length > 0);
  const header = parseLine(lines[0]);
  return lines.slice(1).map((l) => {
    const fields = parseLine(l);
    const row = {};
    header.forEach((h, i) => { row[h] = fields[i]; });
    return row;
  });
}

module.exports = { parseLine, parse };
