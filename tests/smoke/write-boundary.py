#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

"""Refactorings change files outside the workspace only when allowed (WriteBoundary).

The fixture is a Maven project app whose main sources lie outside its folder (sourceDirectory
../shared/src), with a caller in its own test sources. The server is started on app through the
launcher (persistent server, so the registration's --allow-writes travels in the handshake):
  1. a rename that would change ../shared is refused, nothing is written, the files are named;
  2. the same rename with allow_outside succeeds, and back;
  3. a rename inside the workspace needs nothing;
  4. a second connection registered with --allow-writes ../shared renames without allow_outside.

Usage: write-boundary.py <launcher> [work dir]   (exit 0 = pass; launcher: bin/dukehoff-mcp)
"""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import time

launcher = os.path.abspath(sys.argv[1])
work = os.path.abspath(sys.argv[2] if len(sys.argv) > 2 else os.path.join(tempfile.gettempdir(), "dukehoffmcp-write-boundary"))
app = os.path.join(work, "app")
shared = os.path.join(work, "shared")
util = os.path.join(shared, "src", "com", "acme", "Util.java")
use = os.path.join(app, "src", "test", "java", "com", "acme", "UtilUse.java")
data = os.path.join(work, "data")
failures = []


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(text)


def read(path):
    with open(path) as f:
        return f.read()


shutil.rmtree(work, ignore_errors=True)
write(os.path.join(app, "pom.xml"), """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.acme</groupId>
    <artifactId>app</artifactId>
    <version>1.0</version>
    <properties>
        <maven.compiler.release>17</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>
    <build>
        <sourceDirectory>../shared/src</sourceDirectory>
    </build>
</project>
""")
write(util, "package com.acme;\n\npublic class Util {\n    public static int twice(int x) {\n        return 2 * x;\n    }\n}\n")
write(use, "package com.acme;\n\npublic class UtilUse {\n    int value() {\n        return Util.twice(helper());\n    }\n\n"
           "    int helper() {\n        return 1;\n    }\n}\n")


class Session:
    def __init__(self, *options):
        self.p = subprocess.Popen([launcher, "--data", data, *options, app], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  stderr=open(os.path.join(work, "stderr.log"), "a"), text=True, bufsize=1)
        self.id = 0
        self.request("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "wb", "version": "1"}})

    def request(self, method, params):
        self.id += 1
        self.p.stdin.write(json.dumps({"jsonrpc": "2.0", "id": self.id, "method": method, "params": params}) + "\n")
        self.p.stdin.flush()
        while True:
            line = self.p.stdout.readline()
            if not line:
                sys.exit("FAIL: the server ended before answering " + method)
            m = json.loads(line)
            if m.get("id") == self.id:
                return m

    def tool(self, name, arguments):
        r = self.request("tools/call", {"name": name, "arguments": arguments})["result"]
        text = r["content"][0]["text"]
        print(f">>> {name} {json.dumps(arguments)}\n<<< " + ("(error) " if r.get("isError") else "")
              + text[:500].replace("\n", "\n    "), flush=True)
        return bool(r.get("isError")), text

    def close(self):
        self.p.stdin.close()
        self.p.wait(60)


def expect(condition, what):
    print(("ok: " if condition else "FAIL: ") + what, flush=True)
    if not condition:
        failures.append(what)


first = Session()
deadline = time.time() + int(os.environ.get("READY_TIMEOUT", "600"))
while "State: ready" not in first.tool("workspace_status", {})[1] and time.time() < deadline:
    time.sleep(2)
error, text = first.tool("workspace_status", {})
expect("shared" in text and "src" in text, "the source root outside the workspace is open")

before = read(util), read(use)
error, text = first.tool("rename", {"symbol": "com.acme.Util#twice(int)", "new_name": "doubled"})
expect(error and "outside the workspace" in text and util in text and "allow_outside" in text,
       "a rename changing ../shared is refused and names the file")
expect((read(util), read(use)) == before, "nothing was written")

error, text = first.tool("rename", {"symbol": "com.acme.Util#twice(int)", "new_name": "doubled", "allow_outside": [util]})
expect(not error and "doubled(" in read(util) and "Util.doubled(" in read(use), "with allow_outside (the file) it is renamed")
error, text = first.tool("rename", {"symbol": "com.acme.Util#doubled(int)", "new_name": "twice", "allow_outside": "../shared"})
expect(not error and "twice(" in read(util), "and back, allowed as a relative folder given as one string")

error, text = first.tool("rename", {"symbol": "com.acme.UtilUse#helper()", "new_name": "one"})
expect(not error and "one()" in read(use), "a rename inside the workspace needs nothing")
first.close()

second = Session("--allow-writes", "../shared")
error, text = second.tool("rename", {"symbol": "com.acme.Util#twice(int)", "new_name": "doubled"})
expect(not error and "doubled(" in read(util), "a registration with --allow-writes ../shared renames without allow_outside")
second.close()

subprocess.run([launcher, "--data", data, "--stop", app], capture_output=True)
print("WRITE-BOUNDARY TEST " + ("FAILED: " + "; ".join(failures) if failures else "PASSED"))
sys.exit(1 if failures else 0)
