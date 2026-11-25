#!/usr/bin/env python3
"""Manual check of the jharness TUI at several terminal sizes, through tmux (spec 053, D-083).

Usage:
  scripts/tui-tmux-check.py path/to/jharness [--out DIR] [--sizes 80x24,120x40,50x16]

Not run in CI: it complements scripts/tui-pty-check.py (which CI runs) with real terminal sizes, a resize, and the
screen as tmux draws it. Run it after any change to the terminal UI and before each release; docs/manual-checks.md
says how. For each size it starts jharness in a detached tmux session (its own server, no user config) with a
recorded reply that has Markdown, then:

  1. answers the trust question with "n";
  2. sends a prompt and waits for the reply;
  3. checks the reply's styles in `capture-pane -e`: bold, a coloured code line and list bullets;
  4. checks that the status line is on the last row and nothing else is;
  5. resizes the window and checks that the status line moved to the new last row;
  6. sends Ctrl-D and checks that jharness exited 0.

Each capture is saved with its escape codes (DIR/tui-WxH-*.ansi; `cat` one to see it) and as plain text.
Exits 0 if every size passes, 1 if any fails, 2 for bad arguments or no tmux.
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOCKET = "jharness-tui-check-%d" % os.getpid()
REPLY = "\n".join([
    "The TUI check: **bold text** here.",
    "",
    "```java",
    "int answer = 42;",
    "```",
    "",
    "- first item",
    "- second item",
    "",
    "Done.",
])
SGR = re.compile(r"\x1b\[[0-9;]*m")


def tmux(*args, check=True):
    p = subprocess.run(["tmux", "-u", "-L", SOCKET, "-f", os.devnull, *args], capture_output=True, text=True)
    if check and p.returncode != 0:
        raise RuntimeError("tmux " + " ".join(args[:2]) + ": " + p.stderr.strip())
    return p.stdout


def capture(escapes):
    args = ["capture-pane", "-p", "-t", "t"]
    if escapes:
        args.insert(1, "-e")
    return tmux(*args)


def wait_for(text, seconds=15):
    """Waits for text on the screen, with wrapped lines joined (-J), so a long path can't split it."""
    end = time.time() + seconds
    while time.time() < end:
        if text in tmux("capture-pane", "-p", "-J", "-t", "t"):
            return True
        time.sleep(0.1)
    return False


def rows(screen, height):
    lines = screen.split("\n")
    lines = (lines + [""] * height)[:height]
    return lines


def status_row_ok(height, fails, when):
    """The status line ("replay · … · turn 1 …") is the last row, and the row above it isn't another copy."""
    plain = rows(capture(False), height)
    last = plain[height - 1]
    if not last.startswith("replay"):
        fails.append("%s: the last row isn't the status line: %r" % (when, last))
    copies = sum(1 for r in plain if r.startswith("replay ·") or r.strip() == "replay")
    if copies > 1:
        fails.append("%s: %d status lines on the screen" % (when, copies))


def save(out, name, w, h):
    base = os.path.join(out, "tui-%dx%d-%s" % (w, h, name))
    with open(base + ".ansi", "w", encoding="utf-8") as f:
        f.write(capture(True))
    with open(base + ".txt", "w", encoding="utf-8") as f:
        f.write(capture(False))


def check_size(args, w, h, tmp):
    fails = []
    home = os.path.join(tmp, "home-%dx%d" % (w, h))
    ws = os.path.join(tmp, "ws-%dx%d" % (w, h))
    os.makedirs(home)
    os.makedirs(ws)
    replay = os.path.join(tmp, "replay.jsonl")
    cmd = " ".join("'" + a.replace("'", "'\\''") + "'" for a in [args.bin, "--home", home, "--cwd", ws, "--replay", replay])
    tmux("new-session", "-d", "-s", "t", "-x", str(w), "-y", str(h), "env TERM=xterm-256color COLORTERM=truecolor " + cmd)
    tmux("set-option", "-t", "t", "remain-on-exit", "on")
    try:
        if not wait_for("[y] trust"):
            return ["no trust question: " + capture(False).strip()[-300:]]
        tmux("send-keys", "-t", "t", "n", "Enter")
        if not wait_for("/help for commands"):
            return ["no banner after the trust answer"]
        tmux("send-keys", "-t", "t", "Show some Markdown.", "Enter")
        if not wait_for("Done."):
            save(args.out, "noreply", w, h)
            return ["no reply"]
        time.sleep(0.5)
        save(args.out, "reply", w, h)
        styled = capture(True)
        if "\x1b[1mbold text" not in styled:
            fails.append("the bold text isn't bold")
        code = [l for l in styled.split("\n") if "int answer = 42;" in l]
        if not code or not SGR.search(code[0].split("int answer")[0]):
            fails.append("the code line isn't coloured")
        if "**" in SGR.sub("", styled).replace("```", ""):
            fails.append("raw ** left in the reply")
        status_row_ok(h, fails, "%dx%d" % (w, h))
        # A resize: wider and taller, then narrower and shorter.
        for nw, nh in ((w + 20, h + 6), (max(40, w - 20), max(12, h - 6))):
            tmux("resize-window", "-t", "t", "-x", str(nw), "-y", str(nh))
            time.sleep(0.8)
            save(args.out, "resized-%dx%d" % (nw, nh), w, h)
            status_row_ok(nh, fails, "resized to %dx%d" % (nw, nh))
        tmux("send-keys", "-t", "t", "C-d")
        end = time.time() + 10
        while time.time() < end and tmux("display-message", "-p", "-t", "t", "#{pane_dead}").strip() != "1":
            time.sleep(0.1)
        dead = tmux("display-message", "-p", "-t", "t", "#{pane_dead} #{pane_dead_status}").split()
        if dead[:1] != ["1"]:
            fails.append("Ctrl-D didn't end jharness")
        elif dead[1:] != ["0"]:
            fails.append("jharness exited %s" % " ".join(dead[1:]))
        save(args.out, "exit", w, h)
    finally:
        tmux("kill-server", check=False)
    return fails


def main():
    ap = argparse.ArgumentParser(description="Check the jharness TUI at several sizes through tmux.")
    ap.add_argument("bin", help="the jharness binary")
    ap.add_argument("--out", default=os.path.join(ROOT, "target", "tui-check"), help="where captures go (default: target/tui-check)")
    ap.add_argument("--sizes", default="80x24,120x40,50x16", help="WIDTHxHEIGHT list (default: 80x24,120x40,50x16)")
    args = ap.parse_args()
    if not shutil.which("tmux"):
        print("tui-tmux-check: tmux isn't installed (apt install tmux, brew install tmux)", file=sys.stderr)
        return 2
    try:
        sizes = [tuple(int(n) for n in s.lower().split("x")) for s in args.sizes.split(",")]
    except ValueError:
        ap.error("--sizes takes WIDTHxHEIGHT values, such as 80x24")
    if any(w < 40 or h < 12 for w, h in sizes):
        ap.error("each size must be at least 40x12")
    args.bin = os.path.abspath(args.bin)
    if not os.path.exists(args.bin):
        ap.error(args.bin + " doesn't exist")
    os.makedirs(args.out, exist_ok=True)
    tmp = tempfile.mkdtemp(prefix="jharness-tui-check-")
    failed = False
    try:
        with open(os.path.join(tmp, "replay.jsonl"), "w", encoding="utf-8") as f:
            f.write(json.dumps({"reply": REPLY}) + "\n")
        for w, h in sizes:
            try:
                fails = check_size(args, w, h, tmp)
            except RuntimeError as e:
                fails = [str(e)]
            failed = failed or bool(fails)
            print("tui %dx%d %s%s" % (w, h, "fail" if fails else "pass", "".join("\n  " + f for f in fails)), flush=True)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    print("captures in " + args.out + " (cat a .ansi file to see it with colours)")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
