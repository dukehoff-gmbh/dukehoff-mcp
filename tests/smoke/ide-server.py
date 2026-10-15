#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

"""The MCP server inside a running NetBeans IDE (IdeServer), reached with the launcher's --ide.

Our headless runtime stands in for the IDE: it opens a folder with two projects (app and other) and,
with -J-Ddukehoffmcp.ide.server=true, starts the IDE server and announces it in a registry folder
(DUKEHOFF_MCP_IDE_DIR). An agent session started with --ide in app sees only app, with paths relative
to its folder, and refactors there; one in a folder without an open project is told to open it; --ide
without a running IDE fails with a message.

Usage: ide-server.py <launcher> [work dir]   (exit 0 = pass; launcher: bin/dukehoff-mcp)
"""

import glob
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time

launcher = os.path.abspath(sys.argv[1])
work = os.path.abspath(sys.argv[2] if len(sys.argv) > 2 else os.path.join(tempfile.gettempdir(), "dukehoffmcp-ide-server"))
repo = os.path.join(work, "repo")
app = os.path.join(repo, "app")
empty = os.path.join(work, "empty")
registry = os.path.join(work, "registry")
env = dict(os.environ, DUKEHOFF_MCP_IDE_DIR=registry)
failures = []


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(text)


def pom(artifact):
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.acme</groupId>
    <artifactId>{artifact}</artifactId>
    <version>1.0</version>
    <properties>
        <maven.compiler.release>17</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>
</project>
"""


def expect(condition, what):
    print(("ok: " if condition else "FAIL: ") + what, flush=True)
    if not condition:
        failures.append(what)


shutil.rmtree(work, ignore_errors=True)
os.makedirs(empty)
write(os.path.join(app, "pom.xml"), pom("app"))
write(os.path.join(app, "src/main/java/com/acme/Order.java"),
      "package com.acme;\n\npublic class Order {\n    public int total() {\n        return 1;\n    }\n}\n")
write(os.path.join(app, "src/main/java/com/acme/Checkout.java"),
      "package com.acme;\n\npublic class Checkout {\n    int run() {\n        return new Order().total();\n    }\n}\n")
write(os.path.join(repo, "other", "pom.xml"), pom("other"))
write(os.path.join(repo, "other", "src/main/java/com/other/Other.java"), "package com.other;\n\npublic class Other {\n}\n")

# without a running IDE, --ide says so
none = subprocess.run([launcher, "--ide", app], env=env, capture_output=True, text=True, timeout=60)
expect(none.returncode != 0 and "no running NetBeans IDE" in none.stderr, "--ide without an IDE fails with a message")

# the stand-in IDE: the runtime opens repo (app and other) and starts the IDE server
ide = subprocess.Popen([launcher, "--no-daemon", "--data", os.path.join(work, "data"), repo, "--",
                        "-J-Ddukehoffmcp.ide.server=true"], env=env, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL,
                       stderr=open(os.path.join(work, "ide.log"), "w"))
deadline = time.time() + 300
while not glob.glob(os.path.join(registry, "*.json")) and time.time() < deadline:
    time.sleep(1)
entries = glob.glob(os.path.join(registry, "*.json"))
expect(len(entries) == 1, "the IDE announces its server in the registry")
if not entries:
    ide.kill()
    sys.exit("FAIL: no IDE server")


class Agent:
    def __init__(self, folder):
        self.p = subprocess.Popen([launcher, "--ide", folder], env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  stderr=open(os.path.join(work, "agent.log"), "a"), text=True, bufsize=1)
        self.id = 0
        self.request("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "agent", "version": "1"}})

    def request(self, method, params):
        self.id += 1
        self.p.stdin.write(json.dumps({"jsonrpc": "2.0", "id": self.id, "method": method, "params": params}) + "\n")
        self.p.stdin.flush()
        while True:
            line = self.p.stdout.readline()
            if not line:
                sys.exit("FAIL: the connection ended before answering " + method)
            m = json.loads(line)
            if m.get("id") == self.id:
                return m

    def tool(self, name, arguments):
        r = self.request("tools/call", {"name": name, "arguments": arguments})["result"]
        text = r["content"][0]["text"]
        print(f">>> {name} {json.dumps(arguments)}\n<<< " + ("(error) " if r.get("isError") else "")
              + text[:400].replace("\n", "\n    "), flush=True)
        return bool(r.get("isError")), text

    def close(self):
        self.p.stdin.close()
        self.p.wait(60)


agent = Agent(app)
# the stand-in opens its projects in the background: until then, none is open in app
deadline = time.time() + 600
while True:
    error, text = agent.tool("diagnostics", {})
    if not error or time.time() > deadline:
        break
    time.sleep(3)
expect(not error, "diagnostics answers once the project is open in the IDE")
error, text = agent.tool("workspace_status", {})
expect(app in text and "open in the IDE there" in text and "  app" in text and "other" not in text,
       "the agent's workspace is its folder, with only the project open there")
error, text = agent.tool("find_usages", {"symbol": "com.acme.Order#total()"})
expect(not error and "src/main/java/com/acme/Checkout.java" in text and app + "/src" not in text,
       "usages with paths relative to the agent's folder")
error, text = agent.tool("rename", {"symbol": "com.acme.Order#total()", "new_name": "sum"})
expect(not error and "a/src/main/java/com/acme/Checkout.java" in text, "a rename in the IDE's project, diff relative")
with open(os.path.join(app, "src/main/java/com/acme/Checkout.java")) as f:
    expect("new Order().sum()" in f.read(), "the caller is renamed on disk")
error, text = agent.tool("find_symbol", {"query": "Other"})
expect("com.other.Other" not in text, "the other open project is not part of the agent's workspace")
agent.close()

stranger = Agent(empty)
error, text = stranger.tool("diagnostics", {})
expect(error and "No project in" in text and "open" in text, "a folder without an open project is told to open it")
stranger.close()

ide.stdin.close()
try:
    ide.wait(60)
except subprocess.TimeoutExpired:
    ide.kill()
expect(not glob.glob(os.path.join(registry, "*.json")), "the IDE removes its entry when it exits")
print("IDE-SERVER TEST " + ("FAILED: " + "; ".join(failures) if failures else "PASSED"))
sys.exit(1 if failures else 0)
