#!/usr/bin/env python3
"""Drives `spike jline` through a pseudo-terminal and checks raw mode, editing and history (AC-10).

Usage: jline-pty-check.py <path-to-spike-binary-or-java-command...>

Keystrokes sent:
  1. "abc" Enter                         -> "abc"
  2. "abc" Left Left "X" Enter           -> "aXbc"   (cursor movement and insert: line editing)
  3. Up Enter                            -> "aXbc"   (history recall)
  4. Ctrl-D on an empty line             -> ends the reader before its 4-line limit
Prints the probe's JSON line and exits 0 only if every line matches.
"""
import json
import os
import pty
import select
import sys
import time

# JLine turns on application cursor mode (ESC[?1h), where xterm sends arrows as ESC O x.
LEFT = b"\x1bOD"
UP = b"\x1bOA"
KEYS = [b"abc\r", b"abc" + LEFT + LEFT + b"X\r", UP + b"\r", b"\x04"]
EXPECTED = ["abc", "aXbc", "aXbc"]


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
    cmd = sys.argv[1:]
    if not cmd:
        print("usage: jline-pty-check.py <spike command...>", file=sys.stderr)
        return 2
    pid, fd = pty.fork()
    if pid == 0:
        os.environ["TERM"] = "xterm-256color"
        os.execvp(cmd[0], cmd + ["jline", "--lines", "4"])
    transcript = read_available(fd, 5.0)  # start-up and first prompt
    for k in KEYS:
        try:
            os.write(fd, k)
        except OSError:
            break  # the probe exited early; the transcript below says why
        transcript += read_available(fd, 1.0)
    transcript += read_available(fd, 3.0)
    _, status = os.waitpid(pid, 0)

    result = None
    for raw in transcript.decode("utf-8", "replace").replace("\r", "\n").split("\n"):
        start = raw.find('{"probe":"jline"')  # the prompt's escape codes can precede it on the line
        if start >= 0:
            result = json.loads(raw[start:].strip())
    if result is None:
        print("no jline result; transcript:\n" + transcript.decode("utf-8", "replace"), file=sys.stderr)
        return 1
    result["expected"] = EXPECTED
    ok = result.get("lines") == EXPECTED and result.get("ending") == "ctrl-d" and result.get("status") == "pass"
    result["ptyCheck"] = "pass" if ok else "fail"
    print(json.dumps(result))
    return 0 if result["ptyCheck"] == "pass" and os.waitstatus_to_exitcode(status) == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
