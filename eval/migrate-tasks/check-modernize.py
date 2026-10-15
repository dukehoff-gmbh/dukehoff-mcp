#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

"""Checks a modernized legacy-project (run in its directory): every place is modernized or left alone as
expected, the support classes and the tests are unchanged, and all tests pass.

Usage: check-modernize.py <modernize-expected.json>   (exit 0 = correct; prints what is wrong)
"""

import json
import re
import subprocess
import sys

expected = json.load(open(sys.argv[1]))
problems = []

unchanged = ["Ledger", "Session", "Registry", "Bag", "Order"]
paths = [f"src/main/java/com/acme/legacy/{c}.java" for c in unchanged] + ["src/test", "pom.xml"]
if subprocess.run(["git", "diff", "--quiet", "HEAD", "--"] + paths).returncode != 0:
    problems.append("support classes, tests or pom.xml changed: " + subprocess.run(
        ["git", "diff", "--stat", "HEAD", "--"] + paths, capture_output=True, text=True).stdout.strip())


def method_body(source, name):
    m = re.search(r"public static [^(]*\b" + re.escape(name) + r"\(", source)
    if not m:
        return None
    start = source.index("{", m.end())
    depth = 0
    for i in range(start, len(source)):
        if source[i] == "{":
            depth += 1
        elif source[i] == "}":
            depth -= 1
            if depth == 0:
                return source[start:i + 1]
    return None


def ok(kind, transform, body):
    if kind == "twr":
        return ("try (" in body and "finally" not in body) if transform else ("finally" in body and "try (" not in body)
    if kind == "iter":
        foreach = re.search(r"for \([\w<>]+ \w+ :", body) is not None
        return ("Iterator<" not in body and foreach) if transform else ("Iterator<" in body and ".remove()" in body)
    if kind == "god":
        return ("getOrDefault(" in body and "containsKey(" not in body) if transform \
            else ("containsKey(" in body and "getOrDefault(" not in body)
    if kind == "size":
        return ("isEmpty()" in body and "size() == 0" not in body) if transform else ("size() == 0" in body)
    raise ValueError(kind)


sources = {}
missed, broken = [], []
for p in expected["places"]:
    src = sources.setdefault(p["file"], open(p["file"]).read())
    body = method_body(src, p["method"])
    if body is None:
        broken.append(f"{p['method']} ({p['kind']}): method not found")
    elif not ok(p["kind"], p["transform"], body):
        (missed if p["transform"] else broken).append(f"{p['method']} ({p['kind']})")
if missed:
    problems.append(f"{len(missed)} places not modernized: " + ", ".join(missed[:8]))
if broken:
    problems.append(f"{len(broken)} places changed that must stay: " + ", ".join(broken[:8]))

subprocess.run(["rm", "-rf", "target/surefire-reports"])
build = subprocess.run(["mvn", "-o", "-q", "test"], capture_output=True, text=True)
if build.returncode != 0:
    errors = [l for l in build.stdout.splitlines() if "ERROR" in l][:5]
    problems.append("mvn -o test fails: " + " | ".join(errors))
else:
    out = subprocess.run("cat target/surefire-reports/*.txt", shell=True, capture_output=True, text=True).stdout
    run = sum(int(x) for x in re.findall(r"Tests run: (\d+)", out))
    if run != expected["tests"]:
        problems.append(f"{run} tests ran, expected {expected['tests']}")

print("\n".join(problems) if problems else "correct")
sys.exit(1 if problems else 0)
