// JHarness read-only session viewer (spec 048). Text is only ever set with textContent: event text can't become markup.
"use strict";
(function () {
  const token = new URLSearchParams(location.search).get("t") || "";
  const log = document.getElementById("log");
  const meta = document.getElementById("meta");
  const status = document.getElementById("status");
  const agentSel = document.getElementById("agent");
  const showThinking = document.getElementById("thinking");
  const showRaw = document.getElementById("raw");
  const agents = new Set();
  let turnBox = null;
  let turnNo = -1;
  const replies = new Map(); // agent -> element streaming its reply

  function el(tag, cls, text) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined && text !== null) e.textContent = String(text);
    return e;
  }

  function cut(s, n) {
    s = String(s == null ? "" : s);
    return s.length > n ? s.slice(0, n) + "…" : s;
  }

  function box(ev) {
    if (!turnBox || ev.turn !== turnNo) {
      turnNo = ev.turn;
      turnBox = el("section", "turn");
      turnBox.dataset.turn = ev.turn;
      log.appendChild(turnBox);
    }
    return turnBox;
  }

  function add(ev, node) {
    node.dataset.agent = ev.agent || "main";
    filterOne(node);
    box(ev).appendChild(node);
    if (atBottom) window.scrollTo(0, document.body.scrollHeight);
    return node;
  }

  function who(ev, node) {
    if (ev.agent && ev.agent !== "main") node.prepend(el("span", "who", "[" + ev.agent + "]"));
    return node;
  }

  function step(ev, text, cls) {
    return add(ev, who(ev, el("div", "step" + (cls ? " " + cls : ""), "▸ " + text)));
  }

  function diff(text) {
    const pre = el("pre");
    for (const line of String(text).split("\n")) {
      const cls = line.startsWith("+++") || line.startsWith("---") ? "" : line.startsWith("+") ? "add" : line.startsWith("-") ? "del" : line.startsWith("@@") ? "hunk" : "";
      const span = el("span", cls, line + "\n");
      pre.appendChild(span);
    }
    return pre;
  }

  // A reply: the agent's tag, then a body that streamed chunks fill and model.completed replaces. The body shows the
  // text as Markdown (spec 052), redrawn at most once a frame while chunks arrive.
  function reply(ev) {
    let body = replies.get(ev.agent);
    if (!body) {
      const r = el("div", "reply");
      body = el("div", "md");
      body.raw = "";
      r.appendChild(body);
      add(ev, who(ev, r));
      replies.set(ev.agent, body);
    }
    return body;
  }

  function setReply(body, text) {
    body.raw = text;
    if (body.pending) return;
    body.pending = true;
    requestAnimationFrame(() => {
      body.pending = false;
      JHarnessMarkdown.render(body, body.raw);
    });
  }

  function noteAgent(a) {
    if (a && !agents.has(a)) {
      agents.add(a);
      const o = el("option", null, a);
      o.value = a;
      agentSel.appendChild(o);
    }
  }

  function filterOne(node) {
    const a = agentSel.value;
    const hide = (a && node.dataset.agent !== a) || (node.classList.contains("raw") && !showRaw.checked)
      || (node.classList.contains("think") && !showThinking.checked);
    node.classList.toggle("hidden", hide);
  }

  function filterAll() {
    log.querySelectorAll("[data-agent]").forEach(filterOne);
  }
  agentSel.addEventListener("change", filterAll);
  showThinking.addEventListener("change", filterAll);
  showRaw.addEventListener("change", filterAll);

  let atBottom = true;
  window.addEventListener("scroll", () => {
    atBottom = window.innerHeight + window.scrollY >= document.body.scrollHeight - 40;
  });

  function show(ev) {
    noteAgent(ev.agent);
    if (ev.type !== "model.delta" && ev.type !== "model.thinking_delta") {
      const raw = el("div", "raw", (ev.ts || "").slice(11, 19) + "  t" + ev.turn + "  " + (ev.agent || "") + "  " + ev.type);
      add(ev, raw);
    }
    switch (ev.type) {
      case "session.started":
        document.getElementById("title").textContent = "JHarness session " + (ev.session || "");
        meta.textContent = [ev.workspace, ev.provider + " " + ev.model, ev.trusted ? "trusted" : "not trusted", ev.offline ? "offline" : ""].filter(Boolean).join(" · ");
        break;
      case "session.resumed":
        step(ev, (ev.fork ? "forked from " : "resumed ") + ev.from + ": " + ev.turns + " turns, " + ev.messages + " messages");
        break;
      case "turn.started":
        replies.delete(ev.agent);
        add(ev, el("div", "prompt", ev.prompt));
        break;
      case "prompt.attached":
        step(ev, "attached " + (ev.files || []).map(f => f.path).join(", "));
        break;
      case "model.delta": {
        const body = reply(ev);
        setReply(body, body.raw + (ev.text || ""));
        break;
      }
      case "model.completed": {
        setReply(reply(ev), ev.text || "");
        replies.delete(ev.agent);
        if (ev.thinking) {
          const d = el("details", "think");
          d.appendChild(el("summary", null, "thinking"));
          d.appendChild(el("div", "thinking", ev.thinking));
          add(ev, d);
        }
        break;
      }
      case "model.error":
        step(ev, "model error: " + ev.message, "bad");
        break;
      case "model.retry":
        step(ev, "retrying: " + ev.reason + " (attempt " + ev.attempt + " of " + ev.attempts + ")", "ask");
        break;
      case "loop.code":
        replies.delete(ev.agent);
        step(ev, "step " + ev.step + ": running " + ev.lines + " lines");
        break;
      case "loop.result":
        step(ev, "step " + ev.step + ": " + ev.status + (ev.error ? " — " + cut(ev.error, 400) : ""), ev.status === "ok" ? "" : "bad");
        break;
      case "api.call":
        step(ev, ev.api + (ev.summary ? ": " + cut(ev.summary, 200) : "") + (ev.status && ev.status !== "ok" ? " (" + ev.status + ")" : ""), ev.status && ev.status !== "ok" ? "bad" : "");
        break;
      case "permission.asked":
        step(ev, "asked: " + cut(ev.summary, 300), "ask");
        break;
      case "permission.decided":
        step(ev, (ev.allowed ? "allowed: " : "denied: ") + cut(ev.summary, 300) + (ev.reason ? " (" + ev.reason + ")" : ""), ev.allowed ? "" : "bad");
        break;
      case "file.changed":
        step(ev, "changed " + ev.path);
        if (ev.diff) add(ev, diff(ev.diff));
        break;
      case "process.completed":
        step(ev, ev.executable + " exited " + ev.exitCode + " in " + ev.durationMs + " ms" + (ev.timedOut ? " (timed out)" : ""), ev.exitCode === 0 ? "" : "bad");
        break;
      case "subagent.started":
        step(ev, "started" + (ev.background ? " in the background" : "") + ": " + cut(ev.task, 200));
        break;
      case "subagent.completed":
        step(ev, ev.stopReason + " after " + ev.steps + " steps", ev.stopReason === "done" ? "" : "bad");
        break;
      case "context.compacted":
        step(ev, "context compacted: " + ev.tokensBefore + " → " + ev.tokensAfter + " tokens");
        break;
      case "hook.ran":
        if (ev.outcome !== "ok") step(ev, "hook " + ev.hook + ": " + ev.outcome + (ev.reason ? " — " + ev.reason : ""), ev.outcome === "block" ? "bad" : "ask");
        break;
      case "todo.updated":
        step(ev, "todos: " + (ev.items || []).map(i => (i.status === "done" ? "✓ " : i.status === "in_progress" ? "▸ " : "☐ ") + i.text).join("  "));
        break;
      case "turn.completed":
        replies.delete(ev.agent);
        step(ev, "turn " + ev.turn + " " + ev.stopReason + " after " + ev.steps + " steps" + (ev.error ? ": " + ev.error : ""), ev.stopReason === "done" ? "" : "bad");
        break;
      case "result":
        step(ev, "result: " + ev.stopReason + ", exit " + ev.exitCode, ev.exitCode === 0 ? "" : "bad");
        break;
      default:
        break;
    }
  }

  const source = new EventSource("events?t=" + encodeURIComponent(token));
  source.onopen = () => { status.textContent = "live"; status.className = "status live"; };
  source.onerror = () => { status.textContent = "disconnected (the session may have ended)"; status.className = "status"; };
  source.onmessage = (m) => {
    let ev;
    try { ev = JSON.parse(m.data); } catch (e) { return; }
    show(ev);
  };
})();
