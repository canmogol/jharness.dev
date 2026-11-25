// Markdown for the session viewer's replies (spec 052). It builds DOM nodes and sets text only with textContent, so
// a reply can't become markup; links open only for http and https addresses. Covers what models write: fenced code,
// headings, lists, quotes, tables, rules, and inline code, bold, italics, strikethrough and links.
"use strict";
var JHarnessMarkdown = (function () {
  function el(tag, cls, text) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined && text !== null) e.textContent = String(text);
    return e;
  }

  const FENCE = /^ {0,3}(`{3,}|~{3,})\s*([\w+#.-]*)/;
  const HEADING = /^ {0,3}(#{1,6})\s+(.*?)\s*#*\s*$/;
  const ITEM = /^(\s*)([-*+]|\d{1,9}[.)])\s+(.*)$/;
  const RULE = /^ {0,3}([-*_])(\s*\1){2,}\s*$/;
  const QUOTE = /^ {0,3}>\s?(.*)$/;
  const TABLE_SEP = /^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$/;
  // Inline: code, bold, strikethrough, italics, links, bare addresses; the earliest match wins.
  const INLINE = /(`+)([\s\S]*?[^`])\1(?!`)|\*\*(?=\S)([\s\S]*?\S)\*\*|__(?=\S)([\s\S]*?\S)__|~~(?=\S)([\s\S]*?\S)~~|\*(?=[^\s*])([\s\S]*?[^\s*])\*|(?:^|(?<=[^\w]))_(?=[^\s_])([\s\S]*?[^\s_])_(?!\w)|\[([^\]\n]+)\]\(\s*<?([^)\s>]+)>?(?:\s+"[^"]*")?\s*\)|(https?:\/\/[^\s<>()]+[^\s<>().,;:!?'"])/;

  function safeUrl(u) {
    return /^https?:\/\//i.test(u) ? u : null;
  }

  // A link's text is Markdown, except for a bare address, which is its own text.
  function link(parent, text, url, bare) {
    const href = safeUrl(url);
    if (!href) {
      inline(parent, text);
      return;
    }
    const a = el("a", null, bare ? text : undefined);
    a.href = href;
    a.rel = "noopener noreferrer";
    a.target = "_blank";
    if (!bare) inline(a, text);
    parent.appendChild(a);
  }

  function inline(parent, text) {
    let rest = String(text);
    while (rest) {
      const m = INLINE.exec(rest);
      if (!m) {
        parent.appendChild(document.createTextNode(rest));
        return;
      }
      if (m.index > 0) parent.appendChild(document.createTextNode(rest.slice(0, m.index)));
      if (m[1]) {
        parent.appendChild(el("code", null, m[2].replace(/^ (.*) $/, "$1")));
      } else if (m[3] !== undefined || m[4] !== undefined) {
        const b = el("strong");
        inline(b, m[3] !== undefined ? m[3] : m[4]);
        parent.appendChild(b);
      } else if (m[5] !== undefined) {
        const s = el("del");
        inline(s, m[5]);
        parent.appendChild(s);
      } else if (m[6] !== undefined || m[7] !== undefined) {
        const i = el("em");
        inline(i, m[6] !== undefined ? m[6] : m[7]);
        parent.appendChild(i);
      } else if (m[8] !== undefined) {
        link(parent, m[8], m[9]);
      } else {
        link(parent, m[10], m[10], true);
      }
      rest = rest.slice(m.index + m[0].length);
    }
  }

  // Lines joined into one paragraph keep their breaks, as in the terminal.
  function lines(parent, list) {
    list.forEach((l, i) => {
      if (i > 0) parent.appendChild(el("br"));
      inline(parent, l);
    });
  }

  function cells(row) {
    let r = row.trim();
    if (r.startsWith("|")) r = r.slice(1);
    if (r.endsWith("|") && !r.endsWith("\\|")) r = r.slice(0, -1);
    return r.split(/(?<!\\)\|/).map(c => c.trim().replace(/\\\|/g, "|"));
  }

  function table(rows) {
    const t = el("table");
    const head = el("thead");
    const tr = el("tr");
    cells(rows[0]).forEach(c => { const th = el("th"); inline(th, c); tr.appendChild(th); });
    head.appendChild(tr);
    t.appendChild(head);
    const body = el("tbody");
    rows.slice(2).forEach(r => {
      const row = el("tr");
      cells(r).forEach(c => { const td = el("td"); inline(td, c); row.appendChild(td); });
      body.appendChild(row);
    });
    t.appendChild(body);
    const wrap = el("div", "table");
    wrap.appendChild(t);
    return wrap;
  }

  // A list from the lines starting at i; returns [node, next i]. Deeper-indented items make nested lists.
  function list(src, i) {
    const first = ITEM.exec(src[i]);
    const indent = first[1].length;
    const ordered = /\d/.test(first[2]);
    const node = el(ordered ? "ol" : "ul");
    if (ordered && parseInt(first[2], 10) !== 1) node.start = parseInt(first[2], 10);
    let li = null;
    while (i < src.length) {
      const line = src[i];
      const m = ITEM.exec(line);
      if (m && m[1].length === indent && /\d/.test(m[2]) === ordered) {
        li = el("li");
        inline(li, m[3]);
        node.appendChild(li);
        i++;
      } else if (m && m[1].length > indent && li) {
        const [sub, next] = list(src, i);
        li.appendChild(sub);
        i = next;
      } else if (li && line.trim() && /^\s+/.test(line) && !ITEM.exec(line)) {
        li.appendChild(el("br"));
        inline(li, line.trim());
        i++;
      } else {
        break;
      }
    }
    return [node, i];
  }

  function blocks(parent, text) {
    const src = String(text).replace(/\r\n?/g, "\n").split("\n");
    let para = [];
    const flush = () => {
      if (para.length) {
        const p = el("p");
        lines(p, para);
        parent.appendChild(p);
        para = [];
      }
    };
    let i = 0;
    while (i < src.length) {
      const line = src[i];
      let m;
      if ((m = FENCE.exec(line))) {
        flush();
        const close = new RegExp("^ {0,3}" + m[1][0] + "{" + m[1].length + ",}\\s*$");
        const body = [];
        i++;
        while (i < src.length && !close.test(src[i])) body.push(src[i++]);
        i++; // the closing fence, or the end of a reply still streaming
        const pre = el("pre", "code");
        if (m[2]) pre.dataset.lang = m[2];
        pre.appendChild(el("code", null, body.join("\n")));
        parent.appendChild(pre);
      } else if (!line.trim()) {
        flush();
        i++;
      } else if ((m = HEADING.exec(line))) {
        flush();
        const h = el("h" + Math.min(6, m[1].length + 2), "md-h");
        inline(h, m[2]);
        parent.appendChild(h);
        i++;
      } else if (RULE.test(line)) {
        flush();
        parent.appendChild(el("hr"));
        i++;
      } else if (QUOTE.test(line)) {
        flush();
        const q = [];
        while (i < src.length && (m = QUOTE.exec(src[i]))) {
          q.push(m[1]);
          i++;
        }
        const b = el("blockquote");
        blocks(b, q.join("\n"));
        parent.appendChild(b);
      } else if (ITEM.test(line)) {
        flush();
        const [node, next] = list(src, i);
        parent.appendChild(node);
        i = next;
      } else if (line.includes("|") && i + 1 < src.length && TABLE_SEP.test(src[i + 1]) && src[i + 1].includes("-")) {
        flush();
        const rows = [line, src[i + 1]];
        i += 2;
        while (i < src.length && src[i].includes("|") && src[i].trim()) rows.push(src[i++]);
        parent.appendChild(table(rows));
      } else {
        para.push(line);
        i++;
      }
    }
    flush();
  }

  /** Replaces the node's children with the Markdown text rendered as DOM nodes. */
  function render(node, text) {
    while (node.firstChild) node.removeChild(node.firstChild);
    blocks(node, text);
    return node;
  }

  return { render: render };
})();
