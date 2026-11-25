"use strict";

// The total of an order's lines, in cents, after the line discounts.
function calcTotal(lines) {
  let total = 0;
  for (const l of lines) {
    total += l.unitCents * l.quantity - (l.discountCents || 0);
  }
  return total;
}

module.exports = { calcTotal };
