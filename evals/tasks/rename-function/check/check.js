"use strict";
const assert = require("assert");
const fs = require("fs");
const path = require("path");

const order = require(path.resolve("lib/order.js"));
const { invoiceLine } = require(path.resolve("lib/invoice.js"));
const { dailyTotal } = require(path.resolve("lib/report.js"));

assert.strictEqual(typeof order.orderTotal, "function", "order.js exports orderTotal");
assert.strictEqual(order.calcTotal, undefined, "calcTotal is gone");
for (const f of ["lib/order.js", "lib/invoice.js", "lib/report.js"]) {
  assert.ok(!fs.readFileSync(f, "utf8").includes("calcTotal"), f + " still mentions calcTotal");
}
const lines = [{ unitCents: 250, quantity: 2 }, { unitCents: 1000, quantity: 1, discountCents: 100 }];
assert.strictEqual(order.orderTotal(lines), 1400);
assert.strictEqual(invoiceLine({ id: "A7", lines }), "A7: 14.00 EUR");
assert.strictEqual(dailyTotal([{ lines }, { lines }]), 2800);
console.log("ok");
