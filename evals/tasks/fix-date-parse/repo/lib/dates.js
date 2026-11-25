"use strict";

// Parses "YYYY-MM-DD" from the booking form into a UTC Date, or returns null for anything else.
function parseIsoDate(text) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(text);
  if (!m) {
    return null;
  }
  const year = Number(m[1]);
  const month = Number(m[2]);
  const day = Number(m[3]);
  const d = new Date(Date.UTC(year, month, day));
  return d;
}

// "2026-03-01" -> "1 Mar 2026"
function formatShort(date) {
  const months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
  return date.getUTCDate() + " " + months[date.getUTCMonth()] + " " + date.getUTCFullYear();
}

module.exports = { parseIsoDate, formatShort };
