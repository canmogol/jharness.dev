"use strict";
const order = require("./order");

// The sum of every order's total, in cents.
function dailyTotal(orders) {
  return orders.reduce((sum, o) => sum + order.calcTotal(o.lines), 0);
}

module.exports = { dailyTotal };
