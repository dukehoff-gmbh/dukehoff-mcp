#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

"""The rule packs of apply_rule, end to end on the fixtures of eval/migrate-tasks.

- modernize on the legacy project: every place modernized, every look-alike kept, all tests pass
  (eval/migrate-tasks/check-modernize.py).
- junit5 on the JUnit 4 project: no JUnit 4 assertion left, the notes for hand edits in the result,
  the @Test(expected = ...) places left for them.

Usage: rule-packs.py <launcher> [work dir]   (exit 0 = pass; needs Maven with junit-jupiter 5.9.2 and
junit 4.13.2 in the local repository, as the eval does)
"""

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time

launcher = os.path.abspath(sys.argv[1])
work = os.path.abspath(sys.argv[2] if len(sys.argv) > 2 else os.path.join(tempfile.gettempdir(), "dukehoffmcp-rule-packs"))
evals = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "eval", "migrate-tasks")
data = os.path.join(work, "data")
failures = []


def expect(condition, what):
    print(("ok: " if condition else "FAIL: ") + what, flush=True)
    if not condition:
        failures.append(what)


class Session:
    def __init__(self, project):
        self.project = project
        self.p = subprocess.Popen([launcher, "--data", data, project], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  stderr=open(os.path.join(work, "stderr.log"), "a"), text=True, bufsize=1)
        self.id = 0
        self.request("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "packs", "version": "1"}})
        deadline = time.time() + int(os.environ.get("READY_TIMEOUT", "600"))
        while "State: ready" not in self.tool("workspace_status", {})[1] and time.time() < deadline:
            time.sleep(2)

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
        return bool(r.get("isError")), r["content"][0]["text"]

    def close(self):
        self.p.stdin.close()
        self.p.wait(60)
        subprocess.run([launcher, "--data", data, "--stop", self.project], capture_output=True)


shutil.rmtree(work, ignore_errors=True)
os.makedirs(work)
subprocess.run([sys.executable, os.path.join(evals, "make-modern-fixture.py"), work, os.path.join(work, "modernize-expected.json")],
               check=True, capture_output=True)
subprocess.run([sys.executable, os.path.join(evals, "make-fixture.py"), work], check=True, capture_output=True)
# the checks build offline: fetch what the fixtures need once (a no-op when it is in the local repository)
for project in ("legacy-project", "junit4-project"):
    build = subprocess.run(["mvn", "-q", "-B", "test"], cwd=os.path.join(work, project), capture_output=True, text=True)
    expect(build.returncode == 0, f"{project} builds and its tests pass before the packs")

legacy = os.path.join(work, "legacy-project")
s = Session(legacy)
error, text = s.tool("apply_rule", {"pack": "modernize", "max_diff_lines": 1})
expect(not error and "files changed and saved" in text and "Not covered" in text, "the modernize pack applies and adds its notes")
s.close()
check = subprocess.run([sys.executable, os.path.join(evals, "check-modernize.py"), os.path.join(work, "modernize-expected.json")],
                       cwd=legacy, capture_output=True, text=True)
expect(check.returncode == 0, "modernize: every place modernized, every look-alike kept, tests pass: " + check.stdout.strip()[:300])

junit = os.path.join(work, "junit4-project")
s = Session(junit)
error, text = s.tool("apply_rule", {"pack": "junit5", "dry_run": True, "max_diff_lines": 1})
expect(not error and "would change" in text, "the junit5 pack in a dry run changes nothing yet")
error, text = s.tool("apply_rule", {"pack": "junit5", "max_diff_lines": 1})
expect(not error and "Left for hand edits" in text, "the junit5 pack applies and lists what is left for hand edits")
error, text = s.tool("apply_rule", {"pack": "nonsense"})
expect(error and "Packs: junit5, modernize" in text, "an unknown pack names the packs")
s.close()
left = []
for root, _, files in os.walk(os.path.join(junit, "src", "test")):
    for f in files:
        for line in open(os.path.join(root, f)):
            if re.search(r"(^|[^.\w])assert[A-Z]\w*\(", line) and "import" not in line:
                left.append(f + ": " + line.strip())
expect(not left, f"junit5: no JUnit 4 assertion left ({len(left)}: {left[:3]})")
expected = sum(open(os.path.join(root, f)).read().count("@Test(expected") for root, _, files in os.walk(os.path.join(junit, "src", "test")) for f in files)
expect(expected == 3, f"junit5: the 3 @Test(expected = ...) are left for hand edits ({expected})")

print("RULE-PACKS TEST " + ("FAILED: " + "; ".join(failures) if failures else "PASSED"))
sys.exit(1 if failures else 0)
