#!/usr/bin/env python3
"""Evals on real repo tasks (spec 051, D-085).

Runs each task in evals/tasks/ with a jharness binary in headless mode and scores it with the task's hidden check:

  1. Copy the task's repo/ to a fresh folder and commit it with git.
  2. Run the check on the untouched repo: it must fail, or the task is broken.
  3. Run `jharness -p PROMPT --output json --mode MODE` there.
  4. Copy check/ to .eval/ (the agent never sees it) and run the check, or compare the reply.

A task folder holds task.json ({"title", "prompt", "check", "mode"?, "timeout"?}), repo/, check/ and replay.jsonl.
"check" is a list of commands (argv lists) run in the workspace that must all exit 0, or
{"replyContains": [...], "unchanged": true} for a question whose answer is in the reply.

Usage:
  scripts/eval.py path/to/jharness --replay            # the recorded solutions; CI checks the tasks and this script
  scripts/eval.py path/to/jharness --model claude      # a live run with your settings and keys
  scripts/eval.py path/to/jharness --model qwen --label v0.1.0 --out evals/results/v0.1.0-qwen.json

Prints one "eval TASK pass|fail ..." line per task and a score; exits 1 if any task fails, 2 for a broken task or bad arguments.
"""
import argparse
import datetime
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TASKS = os.path.join(ROOT, "evals", "tasks")
CHECK_TIMEOUT = 120


class Broken(Exception):
    """The task itself is wrong: a bad task.json, or a check that passes before the agent ran."""


def load(task_id):
    folder = os.path.join(TASKS, task_id)
    with open(os.path.join(folder, "task.json"), encoding="utf-8") as f:
        t = json.load(f)
    for key in ("title", "prompt", "check"):
        if key not in t:
            raise Broken(task_id + ": task.json needs " + key)
    if not os.path.isdir(os.path.join(folder, "repo")):
        raise Broken(task_id + ": no repo/ folder")
    t["id"] = task_id
    t["folder"] = folder
    return t


def git(ws, *args):
    subprocess.run(["git", "-C", ws, *args], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def prepare(t, ws):
    shutil.copytree(os.path.join(t["folder"], "repo"), ws)
    git(ws, "init", "-q", "-b", "main")
    git(ws, "config", "user.email", "eval@example.invalid")
    git(ws, "config", "user.name", "eval")
    git(ws, "add", ".")
    git(ws, "commit", "-q", "-m", "Initial commit")
    with open(os.path.join(ws, ".git", "info", "exclude"), "a", encoding="utf-8") as f:
        f.write(".eval/\n")


def place_check(t, ws):
    dest = os.path.join(ws, ".eval")
    shutil.rmtree(dest, ignore_errors=True)
    src = os.path.join(t["folder"], "check")
    if os.path.isdir(src):
        shutil.copytree(src, dest)
    else:
        os.makedirs(dest)


def run_check(t, ws, reply):
    """(passed, reason)."""
    check = t["check"]
    if isinstance(check, dict):
        if check.get("unchanged"):
            status = subprocess.run(["git", "-C", ws, "status", "--porcelain"], capture_output=True, text=True).stdout.strip()
            if status:
                return False, "files changed: " + status.replace("\n", ", ")
        missing = [w for w in check.get("replyContains", []) if w not in (reply or "")]
        if missing:
            return False, "the reply doesn't name " + ", ".join(missing)
        return True, ""
    place_check(t, ws)
    try:
        for argv in check:
            try:
                p = subprocess.run(argv, cwd=ws, capture_output=True, text=True, timeout=CHECK_TIMEOUT)
            except subprocess.TimeoutExpired:
                return False, " ".join(argv[:2]) + " timed out"
            if p.returncode != 0:
                out = (p.stderr or p.stdout).strip().splitlines()
                # The assertion or compiler error, not the stack trace's last line.
                errors = [l.strip() for l in out if "Error" in l or "error:" in l]
                line = errors[0] if errors else out[-1] if out else "exit " + str(p.returncode)
                return False, line[:300]
        return True, ""
    finally:
        shutil.rmtree(os.path.join(ws, ".eval"), ignore_errors=True)


def run_task(t, args, tmp):
    ws = os.path.join(tmp, t["id"])
    prepare(t, ws)
    if isinstance(t["check"], list):
        ok, _ = run_check(t, ws, "")
        if ok:
            raise Broken(t["id"] + ": the check passes before the agent ran")
    cmd = [args.bin, "--cwd", ws, "--output", "json", "--mode", t.get("mode", "accept-edits")]
    if args.replay:
        home = os.path.join(tmp, "home-" + t["id"])
        os.makedirs(home)
        cmd += ["--home", home, "--replay", os.path.join(t["folder"], "replay.jsonl")]
    else:
        if args.home:
            cmd += ["--home", args.home]
        if args.model:
            cmd += ["--model", args.model]
    cmd += ["-p", t["prompt"]]
    started = time.monotonic()
    env = dict(os.environ, NO_COLOR="1")
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=t.get("timeout", args.timeout), env=env)
        lines = [l for l in p.stdout.splitlines() if l.strip()]
        result = json.loads(lines[-1]) if lines else {}
        code = p.returncode
    except subprocess.TimeoutExpired:
        result, code = {}, None
    except json.JSONDecodeError:
        result, code = {}, p.returncode
    seconds = round(time.monotonic() - started, 1)
    if code is None:
        passed, reason = False, "jharness timed out"
    elif not result:
        passed, reason = False, "no result object (exit " + str(code) + ")"
    else:
        passed, reason = run_check(t, ws, result.get("reply", ""))
        if passed and code != 0:
            reason = "check passed, but the turn ended with " + str(result.get("stopReason"))
    usage = result.get("usage", {})
    return {
        "task": t["id"], "title": t["title"], "passed": passed, "reason": reason, "exitCode": code,
        "stopReason": result.get("stopReason"), "steps": result.get("steps"), "seconds": seconds,
        "session": result.get("session"), "filesChanged": result.get("filesChanged", []), "usage": usage,
    }


def summary(results):
    passed = sum(1 for r in results if r["passed"])
    total = len(results)
    def add(key):
        return sum(r["usage"].get(key, 0) for r in results)
    s = {"passed": passed, "total": total, "score": round(100.0 * passed / total, 1) if total else 0.0,
         "steps": sum(r["steps"] or 0 for r in results), "seconds": round(sum(r["seconds"] for r in results), 1),
         "inputTokens": add("inputTokens"), "outputTokens": add("outputTokens"), "cacheReadTokens": add("cacheReadTokens"),
         "unpricedRequests": add("unpricedRequests")}
    if any("costUsd" in r["usage"] for r in results):
        s["costUsd"] = round(sum(r["usage"].get("costUsd", 0) for r in results), 6)
    return s


def main():
    ap = argparse.ArgumentParser(description="Run the evals in evals/tasks/ with a jharness binary.")
    ap.add_argument("bin", help="the jharness binary")
    ap.add_argument("--replay", action="store_true", help="use each task's recorded replies (checks the tasks, not a model)")
    ap.add_argument("--model", help="the model profile for a live run (default: your settings' default)")
    ap.add_argument("--home", help="a live run's home folder (default: yours, with its settings and session logs)")
    ap.add_argument("--tasks", help="comma-separated task ids (default: all)")
    ap.add_argument("--timeout", type=int, default=900, help="seconds per task (default: 900)")
    ap.add_argument("--label", default="", help="what this run measures, such as a version")
    ap.add_argument("--out", help="write the results as JSON to this file")
    args = ap.parse_args()
    if args.replay and (args.model or args.home):
        ap.error("--replay doesn't take --model or --home")
    args.bin = os.path.abspath(args.bin)
    ids = args.tasks.split(",") if args.tasks else sorted(d for d in os.listdir(TASKS) if os.path.isdir(os.path.join(TASKS, d)))
    version = subprocess.run([args.bin, "--version"], capture_output=True, text=True).stdout.strip()
    results = []
    tmp = tempfile.mkdtemp(prefix="jharness-eval-")
    try:
        tasks = [load(i) for i in ids]
        for t in tasks:
            r = run_task(t, args, tmp)
            results.append(r)
            u = r["usage"]
            cost = ("$%.4f" % u["costUsd"]) if "costUsd" in u else "-"
            print("eval %s %s  steps=%s tokens=%s cost=%s %.1fs%s" % (r["task"], "pass" if r["passed"] else "fail", r["steps"],
                  u.get("inputTokens", 0) + u.get("outputTokens", 0), cost, r["seconds"], ("  " + r["reason"]) if r["reason"] else ""), flush=True)
    except Broken as e:
        print("eval broken: " + str(e), file=sys.stderr)
        return 2
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    s = summary(results)
    print("score %d/%d (%s%%)  steps=%d tokens=%d%s  %.0fs" % (s["passed"], s["total"], s["score"], s["steps"],
          s["inputTokens"] + s["outputTokens"], ("  cost=$%.4f" % s["costUsd"]) if "costUsd" in s else "", s["seconds"]))
    if args.out:
        doc = {"label": args.label, "version": version, "mode": "replay" if args.replay else "live", "model": args.model,
               "date": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"), "summary": s, "tasks": results}
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump(doc, f, indent=2)
            f.write("\n")
    return 0 if s["passed"] == s["total"] else 1


if __name__ == "__main__":
    sys.exit(main())
