#!/usr/bin/env python3
"""Drives the jharness TUI through a pseudo-terminal with a recorded model reply (spec 005, AC-03, AC-04).

Usage: tui-pty-check.py <jharness-binary>

Sets up a temporary home and workspace, then types:
  1. "change a.txt" Enter        -> the recorded reply writes a.txt; JHarness asks for approval
  2. "y" Enter                   -> approve once; the step runs and the final reply streams
  3. "/help" Enter               -> the command list
  4. Ctrl-D                      -> quit
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

    pid, fd = pty.fork()
    if pid == 0:
        os.environ["TERM"] = "xterm-256color"
        os.execv(binary, [binary, "--home", home, "--cwd", ws, "--replay", replay])
    # A real terminal has a size; without one JLine shortens every prompt.
    fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", 40, 120, 0, 0))
    transcript = read_available(fd, 8.0)
    for keys, wait in [(b"change a.txt\r", 4.0), (b"y\r", 4.0), (b"/help\r", 1.0), (b"\x04", 3.0)]:
        try:
            os.write(fd, keys)
        except OSError:
            break
        transcript += read_available(fd, wait)
    _, status = os.waitpid(pid, 0)
    text = transcript.decode("utf-8", "replace")
    with open(os.path.join(ws, "a.txt")) as f:
        content = f.read()
    checks = {
        "banner": "/help for commands" in text,
        "asked": "Allow writeFile: write a.txt (3 bytes)?" in text,
        "prompt": "> " in text and "[y] once" in text,
        "step": "step 1: ok" in text,
        "reply": "Changed a.txt to new." in text,
        "help": "/model NAME" in text,
        "file": content == "new",
        "exit": os.waitstatus_to_exitcode(status) == 0,
    }
    ok = all(checks.values())
    print(json.dumps({"check": "tui-pty", "status": "pass" if ok else "fail", "checks": checks}))
    if not ok:
        print("transcript:\n" + text, file=sys.stderr)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
