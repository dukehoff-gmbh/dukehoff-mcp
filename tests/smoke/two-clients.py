#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

"""Checks two clients on one workspace, as when Copilot CLI switches sessions (first user site):
client A starts and gets ready, client B starts on the same workspace, then A goes away (stdin
closed, killed after a grace period like an MCP client does). B must keep answering. Also checks
that a call during the first indexing returns "retry" once the wait limit (here 2 s) is reached,
instead of outlasting the client's timeout.

With the persistent server (default) both clients share it; it keeps running without clients, a
new session answers at once, and --stop ends it. With --no-daemon each client must run in its own
JVM: a second NetBeans started on a userdir in use hands its command line to the running one,
whose answers never reached the client.

Usage: two-clients.py <dukehoff-mcp launcher> [work dir] [--no-daemon]   (exit 0 = pass)
"""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import time

args = [a for a in sys.argv[1:] if a != "--no-daemon"]
daemon = len(args) == len(sys.argv) - 1
nb_mcp = os.path.abspath(args[0])
work = os.path.abspath(args[1] if len(args) > 1 else os.path.join(tempfile.gettempdir(), "dukehoffmcp-two-clients"))
workspace = os.path.join(work, "workspace")
data = os.path.join(work, "data")
failures = []


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(text)


def generate():
    shutil.rmtree(work, ignore_errors=True)
    write(os.path.join(workspace, "pom.xml"), """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>test</groupId>
    <artifactId>twoclients</artifactId>
    <version>1.0</version>
    <properties>
        <maven.compiler.release>17</maven.compiler.release>
    </properties>
</project>
""")
    write(os.path.join(workspace, "src/main/java/demo/Greeter.java"),
          "package demo;\n\npublic class Greeter {\n    public String greet(String name) {\n"
          "        return \"Hello \" + name;\n    }\n}\n")


class Client:

    def __init__(self, name, extra=()):
        self.name = name
        self.next_id = 0
        mode = [] if daemon else ["--no-daemon"]
        self.process = subprocess.Popen([nb_mcp, "--data", data, *mode, workspace, *extra],
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=open(os.path.join(work, name + ".stderr"), "w"), text=True, bufsize=1)

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

    def call(self, tool, arguments):
        response = self.request("tools/call", {"name": tool, "arguments": arguments})
        return None if response is None else response["result"]["content"][0]["text"]

    def initialize(self):
        self.request("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                                    "clientInfo": {"name": self.name, "version": "1"}})

    def start(self):
        self.initialize()
        return self.await_ready()

    def await_ready(self):
        start = time.time()
        while True:
            status = self.call("workspace_status", {}) or ""
            if "State: ready" in status or "State: failed" in status or not status or time.time() - start > 300:
                return status
            time.sleep(1)

    def go_away(self, grace=10):
        """What an MCP client does on shutdown: close stdin, then kill after a grace period."""
        self.process.stdin.close()
        try:
            self.process.wait(grace)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait()


def expect(what, ok, detail=""):
    if ok:
        print(f"ok: {what}")
    else:
        failures.append(what)
        print(f"FAIL: {what} {detail}")


generate()
# A waits at most 2 s for the workspace; its first call comes before the first indexing ends
a = Client("a", ["--", "-J-Ddukehoffmcp.wait.seconds=2"])
a.initialize()
started = time.time()
response = a.request("tools/call", {"name": "find_symbol", "arguments": {"query": "Greeter"}})
text = response["result"]["content"][0]["text"] if response else ""
expect("a call during indexing says to retry instead of blocking",
       response and response["result"].get("isError") and "after waiting 2 s" in text and "call this tool again" in text
       and time.time() - started < 30, repr(text))
expect("A is ready", "State: ready" in a.await_ready())
text = a.call("find_symbol", {"query": "Greeter"}) or ""
expect("the retry answers", "demo.Greeter" in text, repr(text))
b = Client("b")
expect("B is ready", "State: ready" in b.start())
text = b.call("find_symbol", {"query": "Greeter"}) or ""
expect("B answers while A runs", "demo.Greeter" in text, text)
a.go_away()
time.sleep(2)
text = b.call("find_symbol", {"query": "Greeter"}) or ""
expect("B answers after A went away", "demo.Greeter" in text, repr(text))
b.go_away(60)


def initializes():
    logs = [os.path.join(dp, f) for dp, _, fs in os.walk(data) for f in fs if f == "messages.log"]
    return [line for log in logs for line in open(log, errors="replace") if "dukehoff-mcp timing: initialize" in line]


def check():
    return subprocess.run([nb_mcp, "--data", data, "--check", workspace], capture_output=True, text=True).stderr


if daemon:
    expect("the server keeps running without a client", "server:    running" in check(), check())
    # a new session: the warm server answers at once, without starting NetBeans or checking the index
    c = Client("c")
    started = time.time()
    c.initialize()
    text = c.call("find_symbol", {"query": "Greeter"}) or ""
    first_answer = time.time() - started
    expect(f"a new session answers from the running server ({first_answer:.1f} s)",
           "demo.Greeter" in text and first_answer < 10, repr(text))
    c.go_away()
    stop = subprocess.run([nb_mcp, "--data", data, "--stop", workspace], capture_output=True, text=True)
    expect("--stop stops the server", "stopped the server" in stop.stderr and "server:" not in check(), stop.stderr)
    # NetBeans writes its log in batches; after the stop it is complete
    jvms = {line.split()[-2] for line in initializes()}
    expect("all three sessions ran in one server", len(initializes()) == 3 and len(jvms) == 1, str(initializes()))
else:
    jvms = {line.split()[-2] for line in initializes()}
    expect("each client has its own JVM", len(initializes()) == 2 and len(jvms) == 2, str(initializes()))
print("TWO CLIENTS PASSED" if not failures else f"TWO CLIENTS FAILED ({len(failures)})")
sys.exit(1 if failures else 0)
