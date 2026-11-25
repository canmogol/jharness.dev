#!/usr/bin/env node
// Manual browser check of the web session viewer (spec 053, D-083). Not run in CI: browsers add minutes per run.
// Run it after any change to the viewer (jharness-web) and before each release; docs/manual-checks.md says how.
//
// Usage:
//   node scripts/viewer-check.mjs path/to/jharness                       # chromium, firefox, webkit
//   node scripts/viewer-check.mjs path/to/jharness --browsers webkit,safari --out target/viewer-check
//
// It records a session whose reply has Markdown (bold, a code block, a list, a table, a link), starts
// `jharness view` on it and opens the address in each browser. For each one it waits for "live", then checks:
//   - the page loaded its scripts and stylesheet;
//   - no console errors, page errors or CSP violations;
//   - no response of 400 or more (the favicon included) and no failed request;
//   - the reply rendered as Markdown: <strong>, <pre>, <li>, <table>, and <a> with an https href;
// and saves a screenshot. It also checks that `jharness view` printed the events in the terminal.
// "safari" drives the real Safari through safaridriver (macOS, "Allow Remote Automation" on); WebDriver has no
// console or network log, so for Safari only the page, CSP violations seen by the page, and Markdown are checked.
//
// Exits 0 if every check passes, 1 if any fails, 2 for bad arguments or a missing tool (Playwright, safaridriver).
import { spawn, spawnSync } from "node:child_process";
import { createRequire } from "node:module";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import net from "node:net";

const ROOT = path.dirname(path.dirname(new URL(import.meta.url).pathname));
const PLAYWRIGHT_BROWSERS = ["chromium", "firefox", "webkit"];
const REPLY = [
  "The viewer check: **bold text** and `inline code`.",
  "",
  "```java",
  "int answer = 42;",
  "```",
  "",
  "- first item",
  "- second item",
  "",
  "| Name | Value |",
  "| --- | --- |",
  "| alpha | 1 |",
  "",
  "See [the docs](https://example.com/docs).",
].join("\n");
const TERMINAL_EVENTS = ["session.started", "turn.started", "model.completed", "turn.completed"];

function usage(msg) {
  if (msg) console.error("viewer-check: " + msg);
  console.error("usage: node scripts/viewer-check.mjs JHARNESS [--browsers chromium,firefox,webkit,safari] [--out DIR] [--timeout SECONDS]");
  process.exit(2);
}

function parseArgs(argv) {
  const a = { browsers: PLAYWRIGHT_BROWSERS, out: path.join(ROOT, "target", "viewer-check"), timeout: 20 };
  const rest = [];
  for (let i = 0; i < argv.length; i++) {
    const v = argv[i];
    if (v === "--browsers") a.browsers = String(argv[++i] || "").split(",").filter(Boolean);
    else if (v === "--out") a.out = path.resolve(String(argv[++i] || ""));
    else if (v === "--timeout") a.timeout = Number(argv[++i]);
    else if (v === "-h" || v === "--help") usage();
    else if (v.startsWith("-")) usage("unknown option " + v);
    else rest.push(v);
  }
  if (rest.length !== 1) usage("give one jharness binary");
  a.bin = path.resolve(rest[0]);
  if (!fs.existsSync(a.bin)) usage(a.bin + " doesn't exist");
  const bad = a.browsers.filter(b => ![...PLAYWRIGHT_BROWSERS, "safari"].includes(b));
  if (!a.browsers.length || bad.length) usage("browsers are chromium, firefox, webkit and safari");
  if (!(a.timeout > 0)) usage("--timeout must be a positive number");
  return a;
}

// Playwright from this folder's node_modules or the global ones (npm install -g playwright).
function loadPlaywright() {
  const roots = [process.cwd(), ROOT];
  const g = spawnSync("npm", ["root", "-g"], { encoding: "utf8" });
  if (g.status === 0) roots.push(g.stdout.trim());
  for (const r of roots) {
    try {
      return createRequire(path.join(r, "noop.js"))("playwright");
    } catch {
      // Try the next place.
    }
  }
  return null;
}

function record(bin, tmp) {
  const home = path.join(tmp, "home");
  const ws = path.join(tmp, "ws");
  fs.mkdirSync(home);
  fs.mkdirSync(ws);
  const replay = path.join(tmp, "replay.jsonl");
  fs.writeFileSync(replay, JSON.stringify({ reply: REPLY }) + "\n");
  const p = spawnSync(bin, ["--home", home, "--cwd", ws, "--replay", replay, "-p", "Show some Markdown."],
    { encoding: "utf8", env: { ...process.env, NO_COLOR: "1" }, timeout: 60_000 });
  if (p.status !== 0) {
    console.error("viewer-check: the replay session failed (exit " + p.status + ")\n" + p.stdout + p.stderr);
    process.exit(1);
  }
  return { home, ws };
}

// Starts `jharness view`; resolves with the address once printed. out() is everything it printed so far.
function startView(bin, s, timeout) {
  const child = spawn(bin, ["view", "--home", s.home, "--cwd", s.ws], { env: { ...process.env, NO_COLOR: "1" } });
  let text = "";
  child.stdout.on("data", d => { text += d; });
  child.stderr.on("data", d => { text += d; });
  const url = new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("jharness view printed no address:\n" + text)), timeout * 1000);
    const look = () => {
      const m = /^Open (http\S+)/m.exec(text);
      if (m) {
        clearTimeout(timer);
        resolve(m[1]);
      }
    };
    child.stdout.on("data", look);
    child.on("exit", code => reject(new Error("jharness view exited " + code + ":\n" + text)));
  });
  return { child, url, out: () => text };
}

// Runs in the page: what loaded and what rendered.
function pageFacts() {
  const md = document.querySelector(".reply .md");
  const a = md && md.querySelector("a");
  const header = document.querySelector("header");
  return {
    status: (document.getElementById("status") || {}).textContent || "",
    styled: !!header && getComputedStyle(header).position === "sticky",
    markdownScript: typeof JHarnessMarkdown === "object",
    appScript: !!document.querySelector(".turn"),
    strong: !!(md && md.querySelector("strong")),
    pre: !!(md && md.querySelector("pre")),
    li: md ? md.querySelectorAll("li").length : 0,
    table: !!(md && md.querySelector("table")),
    link: a ? a.href : "",
    rawMarkdownLeft: md ? /\*\*|```/.test(md.textContent) : true,
    csp: window.__cspViolations || [],
  };
}

const CSP_WATCH = "window.__cspViolations = []; document.addEventListener('securitypolicyviolation', e => "
  + "window.__cspViolations.push(e.violatedDirective + ' ' + (e.blockedURI || '')));";

function judge(f) {
  const fails = [];
  if (!f.styled) fails.push("app.css not applied");
  if (!f.markdownScript) fails.push("markdown.js not loaded");
  if (!f.appScript) fails.push("app.js showed no turn");
  if (!f.strong) fails.push("no <strong>");
  if (!f.pre) fails.push("no <pre>");
  if (f.li < 2) fails.push("no list items");
  if (!f.table) fails.push("no <table>");
  if (!/^https:\/\/example\.com\/docs$/.test(f.link)) fails.push("no <a> to https://example.com/docs (" + (f.link || "none") + ")");
  if (f.rawMarkdownLeft) fails.push("raw ** or ``` left in the reply");
  for (const v of f.csp) fails.push("CSP violation: " + v);
  return fails;
}

async function withPlaywright(pw, name, url, shot, timeout) {
  const fails = [];
  const browser = await pw[name].launch();
  try {
    const page = await browser.newPage({ viewport: { width: 1100, height: 900 } });
    await page.addInitScript(CSP_WATCH);
    page.on("console", m => {
      if (m.type() === "error") fails.push("console error: " + m.text());
    });
    page.on("pageerror", e => fails.push("page error: " + e.message));
    page.on("requestfailed", r => {
      // The event stream is cut when the page closes; that isn't a failure.
      if (!r.url().includes("/events?")) fails.push("request failed: " + r.url().replace(/t=[^&]+/, "t=…") + " " + (r.failure() || {}).errorText);
    });
    const loaded = new Set();
    page.on("response", r => {
      const u = new URL(r.url());
      if (r.status() >= 400) fails.push(r.status() + " for " + u.pathname);
      else loaded.add(u.pathname);
    });
    await page.goto(url);
    await page.waitForFunction(() => document.getElementById("status").textContent === "live", null, { timeout: timeout * 1000 });
    await page.waitForSelector(".reply .md table", { timeout: timeout * 1000 });
    await page.waitForTimeout(1500); // late requests, such as a favicon
    for (const f of ["/app.css", "/app.js", "/markdown.js"]) {
      if (!loaded.has(f)) fails.push(f + " not loaded");
    }
    fails.push(...judge(await page.evaluate(pageFacts)));
    await page.screenshot({ path: shot, fullPage: true });
    return { version: browser.version(), fails };
  } finally {
    await browser.close();
  }
}

async function freePort() {
  return new Promise(resolve => {
    const s = net.createServer();
    s.listen(0, "127.0.0.1", () => {
      const p = s.address().port;
      s.close(() => resolve(p));
    });
  });
}

// The real Safari through safaridriver's WebDriver endpoint.
async function withSafari(url, shot, timeout) {
  const which = spawnSync("which", ["safaridriver"], { encoding: "utf8" });
  if (which.status !== 0) {
    return { skipped: "no safaridriver (Safari on macOS only)" };
  }
  const port = await freePort();
  const driver = spawn("safaridriver", ["-p", String(port)], { stdio: ["ignore", "pipe", "pipe"] });
  let driverErr = "";
  driver.stderr.on("data", d => { driverErr += d; });
  const base = "http://127.0.0.1:" + port;
  const call = async (method, p, body) => {
    const r = await fetch(base + p, { method, headers: { "content-type": "application/json" }, body: body ? JSON.stringify(body) : undefined });
    const j = await r.json();
    if (!r.ok) throw new Error((j.value && (j.value.message || j.value.error)) || ("HTTP " + r.status));
    return j.value;
  };
  let session = null;
  let version = "";
  try {
    for (let i = 0; i < 50; i++) {
      try {
        await call("GET", "/status");
        break;
      } catch {
        await new Promise(r => setTimeout(r, 100));
      }
    }
    try {
      const v = await call("POST", "/session", { capabilities: { alwaysMatch: { browserName: "safari" } } });
      session = v.sessionId;
      version = (v.capabilities && v.capabilities.browserVersion) || "";
    } catch (e) {
      return { setup: "safaridriver couldn't start Safari: " + e.message + (driverErr ? " " + driverErr.trim() : "")
        + "\n  Turn on Safari > Settings > Advanced > \"Show features for web developers\", then Develop > \"Allow Remote Automation\","
        + " and run `safaridriver --enable` once." };
    }
    const s = "/session/" + session;
    await call("POST", s + "/url", { url });
    await call("POST", s + "/execute/sync", { script: CSP_WATCH, args: [] });
    const end = Date.now() + timeout * 1000;
    let facts = null;
    while (Date.now() < end) {
      facts = await call("POST", s + "/execute/sync", { script: "return (" + pageFacts.toString() + ")()", args: [] });
      if (facts.status === "live" && facts.table) break;
      await new Promise(r => setTimeout(r, 200));
    }
    await new Promise(r => setTimeout(r, 1500));
    facts = await call("POST", s + "/execute/sync", { script: "return (" + pageFacts.toString() + ")()", args: [] });
    const fails = facts.status === "live" ? [] : ["status is \"" + facts.status + "\", not live"];
    fails.push(...judge(facts));
    fs.writeFileSync(shot, Buffer.from(await call("GET", s + "/screenshot"), "base64"));
    return { version: "Safari " + version, fails, note: "WebDriver has no console or network log; CSP checked from the page after load" };
  } finally {
    if (session) await call("DELETE", "/session/" + session).catch(() => {});
    driver.kill();
  }
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const needsPw = args.browsers.some(b => PLAYWRIGHT_BROWSERS.includes(b));
  const pw = needsPw ? loadPlaywright() : null;
  if (needsPw && !pw) {
    console.error("viewer-check: Playwright isn't installed. Install it and its browsers (not part of the build or CI):\n"
      + "  npm install -g playwright\n"
      + "  npx playwright install chromium firefox webkit\n"
      + "  # Linux may also need the browsers' system libraries: sudo \"$(command -v npx)\" playwright install-deps");
    process.exit(2);
  }
  fs.mkdirSync(args.out, { recursive: true });
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "jharness-viewer-check-"));
  let failed = false;
  let view = null;
  try {
    const s = record(args.bin, tmp);
    view = startView(args.bin, s, args.timeout);
    const url = await view.url;
    for (const name of args.browsers) {
      const shot = path.join(args.out, "viewer-" + name + "-" + os.platform() + ".png");
      let r;
      try {
        r = name === "safari" ? await withSafari(url, shot, args.timeout) : await withPlaywright(pw, name, url, shot, args.timeout);
      } catch (e) {
        r = { fails: [String(e.message).split("\n")[0]] };
      }
      if (r.skipped) {
        console.log("viewer " + name + " skipped  " + r.skipped);
        continue;
      }
      if (r.setup) {
        console.log("viewer " + name + " fail  " + r.setup);
        failed = true;
        continue;
      }
      const ok = r.fails.length === 0;
      failed = failed || !ok;
      console.log("viewer " + name + " " + (ok ? "pass" : "fail") + "  " + (r.version || "") + "  screenshot=" + shot
        + (r.note ? "  (" + r.note + ")" : "") + (ok ? "" : "\n  " + r.fails.join("\n  ")));
    }
    const missing = TERMINAL_EVENTS.filter(e => !new RegExp("  " + e.replace(".", "\\.") + "\\b").test(view.out()));
    const ok = missing.length === 0;
    failed = failed || !ok;
    console.log("viewer terminal " + (ok ? "pass" : "fail") + "  jharness view printed " + (ok ? TERMINAL_EVENTS.join(", ") : "no " + missing.join(", ")));
  } catch (e) {
    console.log("viewer fail  " + e.message);
    failed = true;
  } finally {
    if (view) view.child.kill();
    fs.rmSync(tmp, { recursive: true, force: true });
  }
  process.exit(failed ? 1 : 0);
}

main();
