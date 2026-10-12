#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

"""Starts dukehoff-mcp on a workspace, waits until it is indexed, then stops it again."""

import json
import subprocess
import sys
import time

nb_mcp, data, workspace = sys.argv[1:4]
server = subprocess.Popen([nb_mcp, "--data", data, workspace], stdin=subprocess.PIPE,
                          stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, bufsize=1)
next_id = 0


def request(method, params):
    global next_id
    next_id += 1
    server.stdin.write(json.dumps({"jsonrpc": "2.0", "id": next_id, "method": method, "params": params}) + "\n")
    server.stdin.flush()
    while True:
        line = server.stdout.readline()
        if not line:
            sys.exit("dukehoff-mcp exited")
        message = json.loads(line)
        if message.get("id") == next_id:
            return message


request("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                       "clientInfo": {"name": "warmup", "version": "1"}})
# the status tool is "status" since the registrations (2026-10-10), "workspace_status" before
names = [t["name"] for t in request("tools/list", {})["result"]["tools"]]
status = "status" if "status" in names else "workspace_status"
start = time.time()
while True:
    text = request("tools/call", {"name": status, "arguments": {}})["result"]["content"][0]["text"]
    if "State: ready" in text:
        break
    if "State: failed" in text or time.time() - start > 900:
        sys.exit(text)
    time.sleep(3)
print("indexed in %.0fs" % (time.time() - start))
server.stdin.close()
try:
    server.wait(60)
except subprocess.TimeoutExpired:
    # NetBeans sometimes does not exit after the client goes away (seen 2026-10-02); a leftover
    # instance would hold the userdir lock and take over the next start
    print("dukehoff-mcp did not exit within 60 s after stdin was closed; killing it", file=sys.stderr)
    subprocess.run(["pkill", "-f", data])
    server.wait(30)
