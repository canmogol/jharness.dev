"use strict";
const assert = require("assert");
const path = require("path");
const { parseLine, parse } = require(path.resolve("lib/csv.js"));

assert.deepStrictEqual(parseLine("a,b,c"), ["a", "b", "c"]);
assert.deepStrictEqual(parseLine('"Smith, Jane",42'), ["Smith, Jane", "42"]);
assert.deepStrictEqual(parseLine('x,"say ""hi""",y'), ["x", 'say "hi"', "y"]);
assert.deepStrictEqual(parseLine('a,,""'), ["a", "", ""]);
assert.deepStrictEqual(parse('name,city\n"Doe, John",Oslo\nAnn,"Paris, FR"\n'),
  [{ name: "Doe, John", city: "Oslo" }, { name: "Ann", city: "Paris, FR" }]);
console.log("ok");
