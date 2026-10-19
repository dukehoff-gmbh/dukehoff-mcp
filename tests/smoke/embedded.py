#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

"""Checks the server embedded in the Apache NetBeans extension for VS Code, without VS Code.

Starts that extension's NetBeans (nbcode) the way the extension does, with our module as an extra
cluster (-J-Dnetbeans.extra.dirs), talks LSP to it like the extension's client (initialize, then the
command dukehoff-mcp.connection, as our VS Code extension calls it), and then MCP through the
launcher's proxy (java -jar dukehoff-mcp.jar --connect <socket> <folder>) on a generated project.

Usage: embedded.py <apache-netbeans extension dir> <our cluster dir> <dukehoff-mcp.jar> [work dir]
  extension dir: the unpacked VSIX's extension/ folder (with nbcode/); cluster dir: the folder with
  config/Modules/com-dukehoff-mcp.xml (runtime/target/dukehoffmcp/extra, only our files are needed).
  Environment: JDK_HOME (JDK for nbcode and the proxy); EMBEDDED_CLAUDE: a claude program, to check
  that Claude Code connects through the entry the extension registers for it.   (exit 0 = pass)
"""

import json
import os
import queue
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time

extension, cluster_src, jar = (os.path.abspath(a) for a in sys.argv[1:4])
work = os.path.abspath(sys.argv[4] if len(sys.argv) > 4 else os.path.join(tempfile.gettempdir(), "dukehoffmcp-embedded"))
jdk = os.environ.get("JDK_HOME")
java = os.path.join(jdk, "bin", "java") if jdk else "java"
failures = []


def expect(what, ok, detail=""):
    print(("ok: " if ok else "FAIL: ") + what + ("" if ok else " " + detail[:500]), flush=True)
    if not ok:
        failures.append(what)


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(text)


# --- our extension's layout: <ext>/nbcode/dukehoffmcp with only our module's files -----------------

shutil.rmtree(work, ignore_errors=True)
cluster = os.path.join(work, "dukehoff-ext", "nbcode", "dukehoffmcp")
for rel in ["config/Modules/com-dukehoff-mcp.xml", "modules/com-dukehoff-mcp.jar", "update_tracking/com-dukehoff-mcp.xml"]:
    os.makedirs(os.path.dirname(os.path.join(cluster, rel)), exist_ok=True)
    shutil.copy(os.path.join(cluster_src, rel), os.path.join(cluster, rel))
shutil.copytree(os.path.join(cluster_src, "modules", "ext", "dukehoff-mcp"), os.path.join(cluster, "modules", "ext", "dukehoff-mcp"))

project = os.path.join(work, "project")
write(os.path.join(project, "pom.xml"), """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>test</groupId>
    <artifactId>embedded</artifactId>
    <version>1.0</version>
    <properties>
        <maven.compiler.release>17</maven.compiler.release>
    </properties>
</project>
""")
write(os.path.join(project, "src/main/java/demo/Greeter.java"),
      "package demo;\n\npublic class Greeter {\n    public String greet(String name) {\n        return \"Hello \" + name;\n    }\n}\n")
write(os.path.join(project, "src/main/java/demo/Main.java"),
      "package demo;\n\npublic class Main {\n    public static void main(String[] args) {\n"
      "        System.out.println(new Greeter().greet(\"world\"));\n    }\n}\n")

# --- nbcode as the Apache extension starts it -----------------------------------------------------------

with socket.socket() as s:
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
nbcode = os.path.join(extension, "nbcode", "bin", "nbcode64.exe" if os.name == "nt" else "nbcode")
userdir = os.path.join(work, "userdir")
os.makedirs(userdir)
args = [nbcode, "--userdir", userdir] + (["--jdkhome", jdk] if jdk else []) + [
    "-J-Dnetbeans.extra.dirs=" + os.path.dirname(cluster) + os.sep + "dukehoffmcp",
    # as the extension does with netbeans.javaSupport.enabled (the default); without it nbcode runs in
    # the mode for coexisting with another Java extension, without NetBeans' Java features
    "--modules", "--direct-disable", "org.netbeans.modules.nbcode.integration.java",
    f"--start-java-language-server=listen:{port}"]
print("nbcode: " + " ".join(args), flush=True)
nb_log = open(os.path.join(work, "nbcode.log"), "w")
nb = subprocess.Popen(args, cwd=userdir, stdin=subprocess.DEVNULL, stdout=nb_log, stderr=subprocess.STDOUT)


def finish():
    nb.terminate()
    try:
        nb.wait(30)
    except subprocess.TimeoutExpired:
        nb.kill()
    print("EMBEDDED PASSED" if not failures else f"EMBEDDED FAILED: {len(failures)} problem(s)")
    sys.exit(1 if failures else 0)


lsp = None
for _ in range(240):
    try:
        lsp = socket.create_connection(("127.0.0.1", port), timeout=5)
        break
    except OSError:
        if nb.poll() is not None:
            break
        time.sleep(0.5)
expect("nbcode accepts the LSP connection", lsp is not None, open(os.path.join(work, "nbcode.log")).read()[-2000:])
if lsp is None:
    finish()
lsp.settimeout(None)
incoming = queue.Queue()


def read_lsp():
    f = lsp.makefile("rb")
    while True:
        length = 0
        while True:
            line = f.readline()
            if not line:
                incoming.put(None)
                return
            line = line.strip()
            if not line:
                break
            if line.lower().startswith(b"content-length:"):
                length = int(line.split(b":")[1])
        incoming.put(json.loads(f.read(length)))


threading.Thread(target=read_lsp, daemon=True).start()
lsp_id = 0


def send(message):
    body = json.dumps(message).encode()
    lsp.sendall(b"Content-Length: %d\r\n\r\n" % len(body) + body)


def lsp_request(method, params, timeout=300):
    """Sends an LSP request; answers the server's own requests meanwhile like a minimal client."""
    global lsp_id
    lsp_id += 1
    send({"jsonrpc": "2.0", "id": lsp_id, "method": method, "params": params})
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            m = incoming.get(timeout=max(0.1, deadline - time.time()))
        except queue.Empty:
            break
        if m is None:
            return None
        if "method" in m and "id" in m:  # a request of the server: configuration gets nulls, the rest null
            items = m.get("params", {}).get("items") if m["method"] == "workspace/configuration" else None
            send({"jsonrpc": "2.0", "id": m["id"], "result": [None] * len(items) if items is not None else None})
        elif m.get("id") == lsp_id:
            return m
    return None


root_uri = "file://" + project.replace(os.sep, "/") if os.name != "nt" else "file:///" + project.replace(os.sep, "/")
init = lsp_request("initialize", {"processId": os.getpid(), "rootUri": root_uri, "capabilities": {},
                                  "workspaceFolders": [{"uri": root_uri, "name": "project"}]})
commands = ((init or {}).get("result", {}).get("capabilities", {}).get("executeCommandProvider") or {}).get("commands", [])
expect("the LSP server offers nbls.dukehoff-mcp.connection", "nbls.dukehoff-mcp.connection" in commands, str(commands)[:500])
send({"jsonrpc": "2.0", "method": "initialized", "params": {}})
answer = lsp_request("workspace/executeCommand", {"command": "nbls.dukehoff-mcp.connection", "arguments": []})
result = (answer or {}).get("result") or {}
expect("the command starts the embedded server and returns its socket", bool(result.get("socket")), json.dumps(answer)[:500])
if not result.get("socket"):
    finish()
again = lsp_request("workspace/executeCommand", {"command": "nbls.dukehoff-mcp.connection", "arguments": []})
expect("a second call returns the same socket", ((again or {}).get("result") or {}).get("socket") == result["socket"], json.dumps(again))

# --- MCP through the proxy, as our VS Code extension registers it --------------------------------------

# as in VS Code: the agent may start the proxy before the extension has written the socket's file
connection_file = os.path.join(work, "connection")
proxy = subprocess.Popen([java, "-jar", jar, "--connect-file", connection_file, project], stdin=subprocess.PIPE,
                         stdout=subprocess.PIPE, stderr=open(os.path.join(work, "proxy.stderr"), "w"), text=True, bufsize=1)
time.sleep(3)
with open(connection_file, "w") as f:
    f.write(result["socket"] + "\n")
mcp_id = 0


def mcp(method, params):
    global mcp_id
    mcp_id += 1
    proxy.stdin.write(json.dumps({"jsonrpc": "2.0", "id": mcp_id, "method": method, "params": params}) + "\n")
    proxy.stdin.flush()
    while True:
        line = proxy.stdout.readline()
        if not line:
            return None
        m = json.loads(line)
        if m.get("id") == mcp_id:
            return m


def call(tool, arguments):
    m = mcp("tools/call", {"name": tool, "arguments": arguments})
    return "" if m is None else m["result"]["content"][0]["text"]


init = mcp("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "embedded", "version": "1"}})
expect("MCP initialize through the proxy", init is not None and "result" in init,
       open(os.path.join(work, "proxy.stderr")).read()[-1000:])
if init is None:
    finish()
started = time.time()
status = ""
while time.time() - started < 600:
    status = call("workspace_status", {})
    if "State: ready" in status or "State: failed" in status:
        break
    time.sleep(2)
expect(f"the workspace is ready in nbcode ({time.time() - started:.0f} s)", "State: ready" in status, status)
claude = os.environ.get("EMBEDDED_CLAUDE")
if claude:
    # what the extension's command "Use the Java tools in Claude Code" registers, in Claude Code's local
    # scope for the project; "claude mcp get" connects to the server to report its state
    entry = json.dumps({"type": "stdio", "command": java, "args": ["-jar", jar, "--connect-file", connection_file, project]})
    env = {k: v for k, v in os.environ.items() if not k.startswith("CLAUDE")}
    run = lambda *args: subprocess.run([claude, "mcp", *args], cwd=project, env=env, capture_output=True, text=True, timeout=300)
    added = run("add-json", "--scope", "local", "java", entry)
    expect("Claude Code takes the server into its local scope", added.returncode == 0, added.stdout + added.stderr)
    got = run("get", "java")
    expect("Claude Code connects to the server inside nbcode", "Connected" in got.stdout and "Failed" not in got.stdout,
           got.stdout + got.stderr)
    run("remove", "--scope", "local", "java")
if os.environ.get("EMBEDDED_PROBE"):
    greeter_uri = root_uri + "/src/main/java/demo/Greeter.java"
    with open(os.path.join(project, "src/main/java/demo/Greeter.java")) as f:
        send({"jsonrpc": "2.0", "method": "textDocument/didOpen", "params": {"textDocument": {
            "uri": greeter_uri, "languageId": "java", "version": 1, "text": f.read()}}})
    refs = lsp_request("textDocument/references", {"textDocument": {"uri": greeter_uri},
                                                   "position": {"line": 3, "character": 18}, "context": {"includeDeclaration": False}})
    print("--- LSP references: " + json.dumps((refs or {}).get("result"))[:600], flush=True)
    for tool, arguments in [("workspace_status", {}), ("find_symbol", {"query": "Greeter"}),
                            ("outline", {"symbol": "demo.Main"}), ("find_implementations", {"symbol": "demo.Greeter"})]:
        print(f"--- {tool}: " + call(tool, arguments)[:600], flush=True)
text = call("find_usages", {"symbol": "demo.Greeter#greet(String)"})
expect("find_usages finds the call in Main.java", "Main.java" in text and "greet(" in text, text)
text = call("rename", {"symbol": "demo.Greeter#greet(String)", "new_name": "welcome"})
expect("rename changes both files", "2 files changed" in text, text)
with open(os.path.join(project, "src/main/java/demo/Main.java")) as f:
    expect("the rename is on disk", "welcome(" in f.read())
# what our extension asks for to show the refactoring for review
answer = lsp_request("workspace/executeCommand", {"command": "nbls.dukehoff-mcp.changes", "arguments": [0]}, timeout=120)
entries = (answer or {}).get("result") or []
renamed = [e for e in entries if "rename" in e.get("summary", "")]
files = renamed[0]["files"] if renamed else []
main_change = [f for f in files if (f.get("newPath") or "").endswith("Main.java")]
expect("the changes command reports the rename with Main.java before and after",
       bool(main_change) and "greet(" in (main_change[0].get("before") or "") and "welcome(" in (main_change[0].get("after") or ""),
       json.dumps(answer)[:800])
text = call("diagnostics", {})
expect("no compile errors after the rename", "No compile errors" in text, text)
gradle_rule = [l for l in open(os.path.join(work, "nbcode.log"), errors="replace") if "GradleSettings" in l]
proxy.stdin.close()
proxy.wait(30)
finish()
