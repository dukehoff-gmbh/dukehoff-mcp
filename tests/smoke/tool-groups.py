#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

"""Checks tool selection (--tools): two registrations with different selections share one persistent
server; each sees only its tools, in tools/list, in the instructions and when calling; an unknown
name fails with the known ones; --no-daemon honors the selection too.

Usage: tool-groups.py <dukehoff-mcp launcher> [work dir]   (exit 0 = pass)
"""

import json
import os
import shutil
import subprocess
import sys
import tempfile

nb_mcp = os.path.abspath(sys.argv[1])
work = os.path.abspath(sys.argv[2] if len(sys.argv) > 2 else os.path.join(tempfile.gettempdir(), "dukehoffmcp-tool-groups"))
workspace = os.path.join(work, "workspace")
data = os.path.join(work, "data")
failures = []
CORE = ["find_usages", "outline", "diagnostics", "rename"]


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(text)


class Client:

    def __init__(self, name, *options):
        self.next_id = 0
        self.stderr = os.path.join(work, name + ".stderr")
        self.process = subprocess.Popen([nb_mcp, "--data", data, *options, workspace], stdin=subprocess.PIPE,
                                        stdout=subprocess.PIPE, stderr=open(self.stderr, "w"), text=True, bufsize=1)

    def request(self, method, params):
        self.next_id += 1
        self.process.stdin.write(json.dumps({"jsonrpc": "2.0", "id": self.next_id, "method": method, "params": params}) + "\n")
        self.process.stdin.flush()
        while True:
            line = self.process.stdout.readline()
            if not line:
                return None
            message = json.loads(line)
            if message.get("id") == self.next_id:
                return message

    def initialize(self):
        return self.request("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                                           "clientInfo": {"name": "t", "version": "1"}})["result"]["instructions"]

    def tools(self):
        return [t["name"] for t in self.request("tools/list", {})["result"]["tools"]]

    def close(self):
        self.process.stdin.close()
        self.process.wait(60)


def expect(what, ok, detail=""):
    if ok:
        print(f"ok: {what}")
    else:
        failures.append(what)
        print(f"FAIL: {what} {detail}")


shutil.rmtree(work, ignore_errors=True)
write(os.path.join(workspace, "pom.xml"), """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>test</groupId>
    <artifactId>toolgroups</artifactId>
    <version>1.0</version>
</project>
""")
write(os.path.join(workspace, "src/main/java/demo/Greeter.java"),
      "package demo;\n\npublic class Greeter {\n    public String greet(String name) {\n        return name;\n    }\n}\n")

copilot = Client("copilot", "--tools", "copilot")
instructions = copilot.initialize()
expect("the copilot preset offers the core tools", copilot.tools() == CORE, copilot.tools())
expect("its instructions name only offered tools",
       "find_usages" in instructions and "find_implementations" not in instructions and "find_symbol" not in instructions,
       instructions)
refused = copilot.request("tools/call", {"name": "find_symbol", "arguments": {"query": "Greeter"}})
expect("a tool outside the selection cannot be called", "error" in refused and "Unknown tool" in refused["error"]["message"],
       refused)

everything = Client("all")
everything.initialize()
expect("another registration on the same server sees all tools", len(everything.tools()) == 13, everything.tools())
check = subprocess.run([nb_mcp, "--data", data, "--check", workspace], capture_output=True, text=True).stderr
expect("both share one server", check.count("server:") == 1, check)
copilot.close()
everything.close()

bogus = Client("bogus", "--tools", "core,refactoring")
bogus.process.stdin.close()
status = bogus.process.wait(60)
message = open(bogus.stderr).read()
expect("an unknown name fails with the known ones",
       status == 2 and "Unknown tool, group or preset 'refactoring'" in message and "refactor (change_signature" in message,
       f"status {status}: {message}")

subprocess.run([nb_mcp, "--data", data, "--stop", workspace], capture_output=True)
single = Client("single", "--no-daemon", "--tools", "core,migrate")
single.initialize()
expect("--no-daemon honors the selection", single.tools() == CORE + ["apply_rule"], single.tools())
single.close()
print("TOOL GROUPS PASSED" if not failures else f"TOOL GROUPS FAILED ({len(failures)})")
sys.exit(1 if failures else 0)
