#!/usr/bin/env python3
"""Copies the agent's guide (jharness-agent/.../guide.md) into README.md's "What it does" section (spec 063).

GuideTest fails when the two differ; run this after editing guide.md.
"""
import os
import re
import sys

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
guide = open(os.path.join(root, "jharness-agent/src/main/resources/dev/jharness/agent/guide.md"), encoding="utf-8").read().strip()
path = os.path.join(root, "README.md")
readme = open(path, encoding="utf-8").read()
pat = re.compile(r"(<!-- guide:[^\n]*-->\n)(.*?)(\n<!-- /guide -->)", re.S)
if not pat.search(readme):
    sys.exit("README.md has no <!-- guide: --> ... <!-- /guide --> section")
new = pat.sub(lambda m: m.group(1) + guide + m.group(3), readme, count=1)
if new != readme:
    open(path, "w", encoding="utf-8").write(new)
    print("README.md updated")
else:
    print("README.md already matches")
