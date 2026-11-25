"use strict";
const assert = require("assert");
const path = require("path");
const { parseIsoDate, formatShort } = require(path.resolve("lib/dates.js"));

assert.strictEqual(formatShort(parseIsoDate("2026-03-01")), "1 Mar 2026");
assert.strictEqual(formatShort(parseIsoDate("2026-12-31")), "31 Dec 2026");
assert.strictEqual(parseIsoDate("2026-02-30"), null, "Feb 30 is not a date");
assert.strictEqual(parseIsoDate("2026-13-01"), null, "month 13 is not a date");
assert.strictEqual(parseIsoDate("2026-00-10"), null, "month 0 is not a date");
assert.strictEqual(parseIsoDate("2024-02-29").toISOString(), "2024-02-29T00:00:00.000Z", "leap day");
assert.strictEqual(parseIsoDate("tomorrow"), null);
console.log("ok");
