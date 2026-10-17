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
around = []
for root, _, files in os.walk(os.path.join(junit, "src", "test")):
    for f in files:
        lines = open(os.path.join(root, f)).read().splitlines()
        for i, line in enumerate(lines):
            if "negativeRange" in line:
                around.append(f + ": " + " | ".join(l.strip() for l in lines[max(0, i - 2):i + 2]))
expected = sum(1 for a in around if "expected = IllegalArgumentException.class" in a)
expect(expected == 3, f"junit5: the 3 expected = IllegalArgumentException.class are left for hand edits ({expected}): {around}")

subprocess.run([sys.executable, os.path.join(evals, "make-jakarta-fixture.py"), work], check=True, capture_output=True)
jee = os.path.join(work, "javaee-project")
build = subprocess.run(["mvn", "-q", "-B", "compile"], cwd=jee, capture_output=True, text=True)
expect(build.returncode == 0, "javaee-project compiles before the pack")
s = Session(jee)
error, text = s.tool("apply_rule", {"pack": "jakarta", "max_diff_lines": 1})
expect(not error and "files changed and saved" in text and "Configuration files" in text,
       "the jakarta pack applies and lists what is left for hand edits")
s.close()
sources = {}
for root, _, files in os.walk(os.path.join(jee, "src")):
    for f in files:
        sources[f] = open(os.path.join(root, f)).read()
code = "\n".join(sources.values())
ee = re.compile(r"\bjavax\.(servlet|persistence|validation|inject|ws\.rs|transaction|annotation\.(PostConstruct|PreDestroy))\b")
left = [l.strip() for l in code.splitlines()
        if ee.search(l) and '"javax.persistence.jdbc.url"' not in l and l.strip() != "import javax.persistence.*;"]
expect(not left, f"jakarta: no Java EE javax reference left ({left[:3]})")
for kept in ("import javax.sql.DataSource;", "import javax.crypto.Cipher;", "import javax.naming.InitialContext;",
             '"javax.persistence.jdbc.url"'):
    expect(kept in code, f"jakarta: the JDK's javax and the string stay: {kept}")
expect("import jakarta.persistence.Entity;" in sources["Category.java"] and "jakarta.servlet.http.HttpServlet" in code,
       "jakarta: the imports name jakarta")
# the hand edits the notes name: the build without the javax APIs, the wildcard import out
pom = os.path.join(jee, "pom.xml")
text = open(pom).read()
text = re.sub(r"\s*<dependency>\s*<groupId>javax\.[^<]*</groupId>.*?</dependency>", "", text, flags=re.S)
open(pom, "w").write(text)
product = os.path.join(jee, "src", "main", "java", "com", "acme", "shop", "Product.java")
migrated = open(product).read()
expect("jakarta.persistence.Entity" in migrated and "javax.persistence.Entity" not in migrated,
       "jakarta: the file with the wildcard import names the jakarta types (written fully qualified)")
open(product, "w").write(migrated.replace("import javax.persistence.*;\n", ""))
build = subprocess.run(["mvn", "-q", "-B", "-o", "clean", "compile"], cwd=jee, capture_output=True, text=True)
expect(build.returncode == 0, "jakarta: compiles against the jakarta APIs alone: "
       + " | ".join(l for l in build.stdout.splitlines() if "ERROR" in l)[:400])

print("RULE-PACKS TEST " + ("FAILED: " + "; ".join(failures) if failures else "PASSED"))
sys.exit(1 if failures else 0)
