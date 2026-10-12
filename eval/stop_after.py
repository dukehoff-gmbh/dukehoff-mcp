#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

"""Runs a Claude Code session (stream-json on stdout) and ends it after N tool calls, from outside,
so the model never learns of a limit (a budget limit is shown to the model and changes its course).

Usage: stop_after.py N OUT.jsonl -- claude ARGS...
Copies the stream to OUT.jsonl. After the N-th tool call it waits until that call's result has
arrived (so the last decision is complete), then terminates the session's process group and appends
{"type": "stopped", "after_tool_calls": N}. Exit status: claude's, or 0 when stopped.
The session is terminated whenever this script ends, also on an error: an orphaned session would
keep editing the workspace (and start servers) while the next session runs.
"""
import json
import os
import signal
import subprocess
import sys


def tool_events(event):
    """The content items of an assistant or user event; "message" is a string in other events."""
    message = event.get("message") if isinstance(event, dict) else None
    content = message.get("content") if isinstance(message, dict) else None
    return [item for item in content if isinstance(item, dict)] if isinstance(content, list) else []


def end_session(proc):
    if proc.poll() is not None:
        return
    try:
        os.killpg(proc.pid, signal.SIGTERM)
        proc.wait(timeout=30)
    except ProcessLookupError:
        pass
    except subprocess.TimeoutExpired:
        os.killpg(proc.pid, signal.SIGKILL)
        proc.wait()


def main():
    limit, out_path = int(sys.argv[1]), sys.argv[2]
    command = sys.argv[sys.argv.index("--") + 1:]
    proc = subprocess.Popen(command, stdout=subprocess.PIPE, start_new_session=True)
    try:
        calls, pending = 0, set()
        with open(out_path, "w", encoding="utf-8") as out:
            for raw in proc.stdout:
                line = raw.decode("utf-8", "replace")
                out.write(line)
                out.flush()
                try:
                    event = json.loads(line)
                except ValueError:
                    continue
                for item in tool_events(event):
                    if item.get("type") == "tool_use":
                        calls += 1
                        if calls <= limit:
                            pending.add(item.get("id"))
                    elif item.get("type") == "tool_result":
                        pending.discard(item.get("tool_use_id"))
                if calls >= limit and not pending:
                    end_session(proc)
                    out.write(json.dumps({"type": "stopped", "after_tool_calls": limit}) + "\n")
                    return 0
        return proc.wait()
    finally:
        end_session(proc)


sys.exit(main())
