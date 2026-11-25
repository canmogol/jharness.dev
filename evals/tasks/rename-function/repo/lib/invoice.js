"use strict";
const { calcTotal } = require("./order");

// One invoice line of text per order, with its total in euros.
function invoiceLine(order) {
  const cents = calcTotal(order.lines);
  return order.id + ": " + (cents / 100).toFixed(2) + " EUR";
}

module.exports = { invoiceLine };
