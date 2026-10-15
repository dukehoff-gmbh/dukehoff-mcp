#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

"""The tools working on the projects open in an IDE instead of on a workspace folder.

Starts the runtime with -J-Ddukehoffmcp.workspace=open-projects: the folder is opened as usual, but the
tools take their source roots from OpenProjects, as they do inside an IDE (Workspace.ofOpenProjects).
Checks that paths are shown in full, that relative paths are resolved against the open project and
that diffs carry absolute paths without git's a/ and b/ prefixes.

Usage: open-projects.py <launcher> [work dir]   (exit 0 = pass; launcher: bin/dukehoff-mcp)
"""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import time

launcher = os.path.abspath(sys.argv[1])
work = os.path.abspath(sys.argv[2] if len(sys.argv) > 2 else os.path.join(tempfile.gettempdir(), "dukehoffmcp-open-projects"))
project = os.path.join(work, "project")
pkg = os.path.join(project, "src", "main", "java", "com", "acme")
failures = []

shutil.rmtree(work, ignore_errors=True)
os.makedirs(pkg)
with open(os.path.join(project, "pom.xml"), "w") as f:
    f.write("""<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.acme</groupId>
    <artifactId>open-projects</artifactId>
    <version>1.0</version>
    <properties>
        <maven.compiler.release>17</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>
</project>
""")
with open(os.path.join(pkg, "Order.java"), "w") as f:
    f.write("package com.acme;\n\npublic class Order {\n    public int total() {\n        return 1;\n    }\n}\n")
with open(os.path.join(pkg, "Checkout.java"), "w") as f:
    f.write("package com.acme;\n\npublic class Checkout {\n    int run() {\n        return new Order().total();\n    }\n}\n")

server = subprocess.Popen([launcher, "--no-daemon", "--data", os.path.join(work, "data"), project,
                           "--", "-J-Ddukehoffmcp.workspace=open-projects"],
                          stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                          stderr=open(os.path.join(work, "stderr.log"), "w"), text=True, bufsize=1)
next_id = 0


def request(method, params):
    global next_id
    next_id += 1
    server.stdin.write(json.dumps({"jsonrpc": "2.0", "id": next_id, "method": method, "params": params}) + "\n")
    server.stdin.flush()
    while True:
        line = server.stdout.readline()
        if not line:
            sys.exit("FAIL: the server ended before answering " + method)
        response = json.loads(line)
        if response.get("id") == next_id:
            return response


def tool(name, arguments):
    result = request("tools/call", {"name": name, "arguments": arguments})["result"]
    text = result["content"][0]["text"]
    print(f">>> {name} {json.dumps(arguments)}\n<<< " + ("(error) " if result.get("isError") else "")
          + text[:400].replace("\n", "\n    "), flush=True)
    return result.get("isError"), text


def expect(condition, what):
    if not condition:
        failures.append(what)
        print("FAIL: " + what, flush=True)


request("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "open-projects", "version": "1"}})
# until the runtime has opened the folder, no project is open: the tools say so
deadline = time.time() + int(os.environ.get("READY_TIMEOUT", "600"))
while True:
    error, text = tool("diagnostics", {})
    if not error or time.time() > deadline:
        break
    expect("No project is open" in text or "not ready" in text, "an error other than 'no project open': " + text)
    time.sleep(2)
expect(not error, "the open project was never served")

order = os.path.join(pkg, "Order.java")
error, text = tool("workspace_status", {})
expect("Workspace: the projects open in the IDE" in text and project in text, "status names the open project in full")
error, text = tool("find_usages", {"symbol": "com.acme.Order#total()"})
expect(not error and os.path.join(pkg, "Checkout.java") in text, "find_usages shows the absolute path")
error, text = tool("diagnostics", {"files": ["src/main/java/com/acme/Order.java"]})
expect(not error and "No errors in 1 file" in text, "a relative path is resolved against the open project")
error, text = tool("diagnostics", {"files": [order]})
expect(not error and "No errors in 1 file" in text, "an absolute path is accepted")
error, text = tool("rename", {"symbol": "com.acme.Order#total()", "new_name": "sum"})
expect(not error and f"--- {order}\n" in text and "a/" + order not in text, "the diff carries absolute paths without a/")
with open(os.path.join(pkg, "Checkout.java")) as f:
    expect("new Order().sum()" in f.read(), "the caller was renamed on disk")

server.stdin.close()
try:
    server.wait(60)
except subprocess.TimeoutExpired:
    server.kill()
    expect(False, "the server did not exit after stdin was closed")
print("OPEN-PROJECTS TEST " + ("FAILED: " + "; ".join(failures) if failures else "PASSED"))
sys.exit(1 if failures else 0)
