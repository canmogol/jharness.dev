// A small DOM for ViewerMarkdownTest: enough of document and Element for markdown.js, and a serializer that shows
// which nodes it built. Text is escaped when serialized, so markup in the input shows as &lt; in the output.
"use strict";
function Node(tag, text) {
  this.tagName = tag;
  this.text = text;
  this.childNodes = [];
  this.className = "";
  this.dataset = {};
}
Object.defineProperty(Node.prototype, "firstChild", { get() { return this.childNodes[0] || null; } });
Object.defineProperty(Node.prototype, "textContent", {
  get() { return this.tagName === "#text" ? this.text : this.childNodes.map(c => c.textContent).join(""); },
  set(v) { this.childNodes = [new Node("#text", String(v))]; },
});
Node.prototype.appendChild = function (c) { this.childNodes.push(c); return c; };
Node.prototype.removeChild = function (c) { this.childNodes.splice(this.childNodes.indexOf(c), 1); return c; };
var document = {
  createElement(tag) { return new Node(tag); },
  createTextNode(t) { return new Node("#text", String(t)); },
};
function esc(s) { return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;"); }
function html(n) {
  if (n.tagName === "#text") return esc(n.text);
  let a = "";
  if (n.className) a += ' class="' + n.className + '"';
  if (n.href) a += ' href="' + esc(n.href) + '"';
  if (n.rel) a += ' rel="' + n.rel + '"';
  if (n.start) a += ' start="' + n.start + '"';
  if (n.dataset.lang) a += ' lang="' + n.dataset.lang + '"';
  if (n.tagName === "br" || n.tagName === "hr") return "<" + n.tagName + ">";
  return "<" + n.tagName + a + ">" + n.childNodes.map(html).join("") + "</" + n.tagName + ">";
}
function md(text) {
  const root = new Node("div");
  JHarnessMarkdown.render(root, text);
  return root.childNodes.map(html).join("");
}
