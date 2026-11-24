#!/usr/bin/env python3
"""Drives the jharness TUI through a pseudo-terminal with a recorded model reply (spec 005, AC-03, AC-04).

Usage: tui-pty-check.py <jharness-binary>

Sets up a temporary home and workspace, then types:
  0. "n" Enter                   -> don't trust the new folder (the trust question comes first)
  1. "change a.txt" Enter        -> the recorded reply writes a.txt; JHarness asks for approval
  2. "y" Enter                   -> approve once; the step runs and the final reply streams
  3. "/help" Enter               -> the command list
  4. "/undo" Enter               -> a.txt is back to "old"
  5. "spin" Enter, then Esc      -> the recorded reply loops forever; Esc stops it
  6. "/" Tab, then Ctrl-U        -> the command list again, from Tab (spec 024)
  7. Ctrl-D                      -> quit
Exits 0 only if the approval was asked, the file changed, the reply was shown and JHarness exited 0.
"""
import fcntl
import json
import os
import struct
import termios
import pty
import select
import sys
import tempfile
import time


def read_available(fd, seconds):
    out = b""
    end = time.time() + seconds
    while time.time() < end:
        r, _, _ = select.select([fd], [], [], 0.05)
        if r:
            try:
                chunk = os.read(fd, 4096)
            except OSError:
                break
            if not chunk:
                break
            out += chunk
    return out


def main():
    if len(sys.argv) != 2:
        print("usage: tui-pty-check.py <jharness-binary>", file=sys.stderr)
        return 2
    binary = os.path.abspath(sys.argv[1])
    tmp = tempfile.mkdtemp(prefix="jharness-tui-")
    home = os.path.join(tmp, "home")
    ws = os.path.join(tmp, "ws")
    os.makedirs(home)
    os.makedirs(ws)
    with open(os.path.join(ws, "a.txt"), "w") as f:
        f.write("old")
    replay = os.path.join(tmp, "replay.jsonl")
    with open(replay, "w") as f:
        f.write(json.dumps({"reply": "Changing it.\n```js\napi.writeFile('a.txt', 'new')\n```"}) + "\n")
        f.write(json.dumps({"reply": "Changed a.txt to new."}) + "\n")
        f.write(json.dumps({"reply": "Spinning.\n```js\nwhile (true) {}\n```"}) + "\n")
        # A background subagent that spins; main waits until it has taken its reply (spec 030).
        f.write(json.dumps({"reply": "```js\napi.spawnAgent({task: 'spin', background: true});\n"
                            "for (let i = 0; i < 200 && JSON.parse(api.agents())[0].steps < 1; i++) "
                            "{ api.waitAgent({agent: 'sub-1', timeoutMs: 50}); }\n'started'\n```"}) + "\n")
        f.write(json.dumps({"reply": "```js\nwhile (true) {}\n```"}) + "\n")
        f.write(json.dumps({"reply": "Background started."}) + "\n")

    pid, fd = pty.fork()
    if pid == 0:
        os.environ["TERM"] = "xterm-256color"
        os.execv(binary, [binary, "--home", home, "--cwd", ws, "--replay", replay])
    # A real terminal has a size; without one JLine shortens every prompt.
    fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", 40, 120, 0, 0))
    transcript = read_available(fd, 8.0)
    steps = [(b"n\r", 2.0), (b"change a.txt\r", 4.0), (b"y\r", 4.0), (b"/help\r", 1.0), (b"/undo\r", 1.5),
             (b"spin\r", 2.5), (b"\x1b", 2.5),
             (b"bg\r", 5.0), (b"/follow\r", 1.5), (b"\x1b", 1.5), (b"/stop all\r", 4.0), (b"/\t", 1.5), (b"\x15", 0.5), (b"\x04", 3.0)]
    content_after_change = None
    help_end = 0
    tab_help = False
    for keys, wait in steps:
        try:
            os.write(fd, keys)
        except OSError:
            break
        transcript += read_available(fd, wait)
        if keys == b"/\t":
            tab_help = "/model NAME" in transcript[help_end:].decode("utf-8", "replace")
        if keys == b"/help\r":
            help_end = len(transcript)
            with open(os.path.join(ws, "a.txt")) as f:
                content_after_change = f.read()
    _, status = os.waitpid(pid, 0)
    text = transcript.decode("utf-8", "replace")
    with open(os.path.join(ws, "a.txt")) as f:
        content = f.read()
    checks = {
        "trust": "Do you trust " in text and "loosen JHarness rules" in text,
        "banner": "/help for commands" in text,
        "asked": "Allow writeFile: write a.txt (3 bytes)?" in text,
        "prompt": "> " in text and "[y] once" in text,
        "step": "step 1: ok" in text,
        "reply": "Changed a.txt to new." in text,
        "help": "/model NAME" in text,
        "tab-help": tab_help,
        "file": content_after_change == "new",
        "diff": "-old" in text and "+new" in text,
        "undo": "Restored a.txt." in text and content == "old",
        "esc": "[interrupted]" in text,
        "follow": "Following sub-1" in text and "Stopped following sub-1; it keeps running" in text and "Stopped sub-1." in text,
        "exit": os.waitstatus_to_exitcode(status) == 0,
        "trust-saved": os.path.exists(os.path.join(home, ".jharness", "trusted.json")),
    }
    ok = all(checks.values())
    print(json.dumps({"check": "tui-pty", "status": "pass" if ok else "fail", "checks": checks}))
    if not ok:
        print("transcript:\n" + text, file=sys.stderr)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
