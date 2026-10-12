#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

"""Turns the stream-json transcript of one run into a row of runs.tsv.

Usage: metrics.py <run.jsonl> <task> <mode> <rep> <check exit status> <wall seconds> [<server log>]

The server log (NetBeans' messages.log of the session) gives the time dukehoff-mcp needed to start
(JVM start to the MCP initialize request) and the time tool calls waited for the initial scan.
Columns: see COLUMNS in summarize.py.
"""

import collections
import json
import re
import sys

path, task, mode, rep, status, wall = sys.argv[1:7]
server_log = sys.argv[7] if len(sys.argv) > 7 and sys.argv[7] != "-" else None


def server_times(log):
    """Seconds from JVM start to initialize, and seconds tool calls waited for the initial scan
    (concurrent waits counted once)."""
    startup, waits = 0.0, []
    for line in open(log, encoding="utf-8", errors="replace"):
        if "dukehoff-mcp timing: " not in line:
            continue
        words = line.split("dukehoff-mcp timing: ", 1)[1].split()
        if words[0] == "initialize":
            startup = (int(words[2]) - int(words[1])) / 1000
        elif words[:2] == ["startup", "wait"]:
            waits.append((int(words[2]), int(words[3])))
    waited, end = 0, 0
    for a, b in sorted(waits):
        a = max(a, end)
        if b > a:
            waited += b - a
        end = max(end, b)
    return startup, waited / 1000


result = {}
model = ""
mcp_status = "-"
calls = collections.Counter()
names_by_id = {}
mcp_errors = 0
for line in open(path, encoding="utf-8"):
    try:
        event = json.loads(line)
    except ValueError:
        continue
    kind = event.get("type")
    if kind == "system" and event.get("subtype") == "init":
        model = event.get("model", "")
        servers = event.get("mcp_servers") or []
        mcp_status = ",".join("%s=%s" % (s.get("name"), s.get("status")) for s in servers) or "-"
    elif kind == "assistant":
        for item in event.get("message", {}).get("content", []):
            if item.get("type") == "tool_use":
                calls[item.get("name")] += 1
                names_by_id[item.get("id")] = item.get("name")
    elif kind == "user":
        content = event.get("message", {}).get("content", [])
        for item in content if isinstance(content, list) else []:
            if (item.get("type") == "tool_result" and item.get("is_error")
                    and names_by_id.get(item.get("tool_use_id"), "").startswith("mcp__")):
                mcp_errors += 1
    elif kind == "result":
        result = event

usage = result.get("usage", {})
tokens = sum(usage.get(k, 0) for k in ("input_tokens", "cache_creation_input_tokens",
                                       "cache_read_input_tokens", "output_tokens"))
mcp_calls = sum(n for name, n in calls.items() if name.startswith("mcp__"))
startup, waited = server_times(server_log) if server_log else ("", "")
tools = " ".join("%s:%d" % (re.sub(r"^mcp__([\w-]+?)(-java)?__", lambda m: ("nb" if m.group(1) == "netbeans" else m.group(1)) + ".", name), n)
                 for name, n in calls.most_common())
print("\t".join(map(str, [task, mode, rep, "pass" if status == "0" else "FAIL",
                          result.get("num_turns", ""), tokens, usage.get("output_tokens", ""),
                          result.get("total_cost_usd", ""), wall, sum(calls.values()), mcp_calls,
                          mcp_errors, mcp_status, model, tools or "-", result.get("subtype", "none"),
                          round(result.get("duration_api_ms", 0) / 1000, 1), startup, waited])))
