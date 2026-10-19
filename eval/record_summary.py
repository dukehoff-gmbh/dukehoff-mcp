#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

"""Summarizes a recording of record.sh: per pane the real time, the check and the tool calls, read
from the session's transcript (Claude Code keeps it under ~/.claude/projects/<folder>/)."""

import collections
import glob
import json
import os
import re
import sys

out, modes = sys.argv[1], sys.argv[2:]
LABELS = {"baseline": "A, without Dukehoff MCP", "netbeans": "B, with Dukehoff MCP"}
started = os.path.getmtime(os.path.join(out, "go"))


def read(name, default=""):
    try:
        with open(os.path.join(out, name)) as f:
            return f.read().strip()
    except OSError:
        return default


def transcript(work):
    folder = re.sub(r"[^A-Za-z0-9]", "-", os.path.realpath(work))
    files = [f for f in glob.glob(os.path.expanduser(f"~/.claude/projects/{folder}/*.jsonl"))
             if os.path.getmtime(f) >= started]
    return max(files, key=os.path.getmtime) if files else None


print(f"# {os.path.basename(out)}\n")
for mode in modes:
    elapsed = read(f"{mode}.elapsed")
    time = f"{int(elapsed) // 60}:{int(elapsed) % 60:02d}" if elapsed else "-"
    check = read(f"{mode}.check").splitlines()
    print(f"## {LABELS.get(mode, mode)}\n")
    print(f"- time: {time}")
    print(f"- result: {read(f'{mode}.result', '-')}" + (f" ({check[-1]})" if check else ""))
    path = transcript(read(f"{mode}.work"))
    if not path:
        print("- transcript: not found\n")
        continue
    calls = collections.Counter()
    messages = set()
    output_tokens = 0
    for line in open(path):
        try:
            event = json.loads(line)
        except ValueError:
            continue
        message = event.get("message")
        if event.get("type") != "assistant" or not isinstance(message, dict):
            continue
        if message.get("id") not in messages:
            messages.add(message.get("id"))
            output_tokens += (message.get("usage") or {}).get("output_tokens", 0)
        for content in message.get("content") or []:
            if content.get("type") == "tool_use":
                name = content["name"]
                if name == "Bash":
                    # the command, not a "cd <dir> &&" (or ";" or a new line) in front of it
                    command = re.sub(r"^\s*cd\s+\S+\s*(&&|;|\n)\s*", "", content["input"].get("command", ""))
                    name = "Bash: " + (command.split() or ["?"])[0]
                calls[name] += 1
    mcp = {name: n for name, n in calls.items() if name.startswith("mcp__")}
    print(f"- model responses: {len(messages)}, tool calls: {sum(calls.values())}, output tokens: {output_tokens}")
    print("- tools: " + ", ".join(f"{name} {n}" for name, n in calls.most_common()))
    if mode == "netbeans":
        print("- Dukehoff MCP calls: " + (", ".join(f"{name.split('__')[-1]} {n}" for name, n in mcp.items()) or "none"))
    print(f"- transcript: {path}\n")
