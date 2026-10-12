#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

"""Attributes every tool call of Claude Code sessions to what the agent was doing: read, search,
edit, compile, test, dependency, web, vcs, dukehoff-mcp or meta. For each category: calls, the share of
all calls, and the characters its results put into the context (what a replacement tool could make
compact). Shows where replacement tools would save most (roadmap item 7, baseline measurement).
Output tokens per category are not reported: stream-json's assistant events carry partial usage.

Usage: turns.py <results dir or run.jsonl>...   (prints Markdown; runs grouped by task)
"""

import collections
import json
import os
import re
import sys

CATEGORIES = ["read", "search", "edit", "compile", "test", "dependency", "web", "vcs", "dukehoff-mcp", "meta"]
FILE_TOOLS = {"Read": "read", "NotebookRead": "read", "Grep": "search", "Glob": "search", "LS": "search",
              "Edit": "edit", "MultiEdit": "edit", "Write": "edit", "NotebookEdit": "edit",
              "WebFetch": "web", "WebSearch": "web"}


def bash_category(command):
    """The category of a shell command; build and test commands win over the reads around them."""
    words = re.findall(r"[^\s;&|()]+", command)
    if "mvn" in words or any(w.endswith("/mvnw") or w == "./mvnw" for w in words):
        goals = " ".join(words)
        if "dependency:" in goals:
            return "dependency"
        if re.search(r"(^|\s)(test|verify|package|install)(\s|$)|-Dtest=", goals) and "-DskipTests" not in goals:
            return "test"
        return "compile"
    if "gradle" in words or any(w.endswith("gradlew") for w in words):
        return "test" if re.search(r"(^|\s)(test|check|build)(\s|$)", " ".join(words)) else "compile"
    if "javac" in words:
        return "compile"
    if "java" in words:
        return "test"
    if "git" in words:
        return "vcs"
    if any(w in ("grep", "rg", "find", "ls", "tree", "fd", "ag") for w in words):
        return "search"
    if any(w in ("cat", "head", "tail", "sed", "less", "awk", "wc") for w in words):
        return "read"
    return "meta"


def category(name, arguments):
    if name.startswith("mcp__"):
        return "dukehoff-mcp"
    if name == "Bash":
        return bash_category(arguments.get("command", ""))
    return FILE_TOOLS.get(name, "meta")


def result_chars(content):
    if isinstance(content, str):
        return len(content)
    return sum(len(c.get("text", "")) for c in content or [] if isinstance(c, dict))


def analyze(path):
    """Per category: [calls, result chars]; and the number of turns."""
    stats = {c: [0, 0] for c in CATEGORIES}
    pending = {}
    turns = 0
    for line in open(path, encoding="utf-8"):
        try:
            event = json.loads(line)
        except ValueError:
            continue
        message = event.get("message", {})
        if event.get("type") == "assistant":
            turns += 1
            for use in (c for c in message.get("content", []) if c.get("type") == "tool_use"):
                cat = category(use.get("name", ""), use.get("input", {}))
                pending[use.get("id")] = cat
                stats[cat][0] += 1
        elif event.get("type") == "user" and isinstance(message.get("content"), list):
            for item in message["content"]:
                if item.get("type") == "tool_result" and item.get("tool_use_id") in pending:
                    stats[pending[item["tool_use_id"]]][1] += result_chars(item.get("content"))
        elif event.get("type") == "result" and event.get("num_turns"):
            # assistant events can be one per content block; the session's own count is exact
            turns = event["num_turns"]
    return stats, turns


def table(title, stats, runs, turns):
    calls = sum(s[0] for s in stats.values()) or 1
    chars = sum(s[1] for s in stats.values()) or 1
    lines = [f"### {title} ({runs} run{'s' if runs != 1 else ''}, {turns / runs:.0f} turns and "
             f"{calls / runs:.0f} tool calls per run)", "",
             "| category | calls/run | share of calls | result KB/run | share of results |",
             "|---|---:|---:|---:|---:|"]
    for c in CATEGORIES:
        n, r = stats[c]
        if n:
            lines.append(f"| {c} | {n / runs:.1f} | {100 * n / calls:.0f}% | {r / runs / 1024:.1f} | "
                         f"{100 * r / chars:.0f}% |")
    return "\n".join(lines) + "\n"


def runs_in(arg):
    if os.path.isdir(arg):
        return sorted(os.path.join(arg, f) for f in os.listdir(arg) if f.endswith(".jsonl"))
    return [arg]


by_task = collections.defaultdict(list)
for arg in sys.argv[1:]:
    for path in runs_in(arg):
        # <task>-<mode>-<rep>.jsonl, as run.sh names them
        task = re.sub(r"-[a-z]+-\d+\.jsonl$", "", os.path.basename(path))
        by_task[task].append(analyze(path))
total = {c: [0, 0] for c in CATEGORIES}
total_runs = total_turns = 0
for task, results in sorted(by_task.items()):
    stats = {c: [sum(r[0][c][i] for r in results) for i in range(2)] for c in CATEGORIES}
    turns = sum(r[1] for r in results)
    print(table(task, stats, len(results), turns))
    for c in CATEGORIES:
        for i in range(2):
            total[c][i] += stats[c][i]
    total_runs += len(results)
    total_turns += turns
if len(by_task) > 1:
    print(table("all tasks", total, total_runs, total_turns))
