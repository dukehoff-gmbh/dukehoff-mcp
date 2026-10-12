#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

"""Makes an unfamiliar copy of Apache Commons Lang 3.17.0 for evaluations: models know Commons Lang
from training and went straight to an injected bug without navigating (eval/RESULTS.md, everyday
tasks). The copy compiles and passes its tests like the original, but nothing names it:

1. Text, where it is safe: package org.apache.commons.lang3 -> com.lumens.toolkit.base3
   (directories too), license headers, @since lines, LANG-nnnn issue ids and Apache/Commons
   mentions removed, only sources and tests kept, a minimal pom instead of commons-parent.
2. Semantic renames with dukehoff-mcp's rename tool (each must pass its compile check): the *Utils
   classes (-> *Tools), their test classes, and the methods the tasks use. Text replacement would
   break here: regionMatches, equalsIgnoreCase and friends also exist on String.
3. Old class names left in comments and strings are replaced (distinctive names only), the full
   test suite must pass, then the result becomes a fresh repository with a single commit.

Names keep their lengths (package segments 3.6.7.5, Utils -> Tools): tests of abbreviated class
names ("o.a.c.l.ClassUtils" for 18 characters) depend on them.

The copy is for local evaluations only and is not distributed; the Apache License text stays in
LICENSE.txt. Resumable: renames already done are skipped.

Usage: make-copy.py <commons-lang checkout at rel/commons-lang-3.17.0> <new directory> <dukehoff-mcp launcher>
"""

import json
import os
import re
import shutil
import subprocess
import sys
import time

# same segment lengths as org.apache.commons.lang3 (3.6.7.5) and the same class name lengths
# (*Utils -> *Tools): tests of abbreviated class names depend on them
OLD_PKG, NEW_PKG = "org.apache.commons.lang3", "com.lumens.toolkit.base3"
SPECIAL = {"StringUtils": "TextTools", "CharSequenceUtils": "SequenceTools", "StringEscapeUtils": "EscapeTools",
           "RandomStringUtils": "RandomTextTools"}
# methods the tasks touch: (type after the class renames, old name, parameter types or None, new name)
METHODS = [
    ("SequenceTools", "regionMatches", None, "segmentsMatch"),
    ("TextTools", "countMatches", "CharSequence,CharSequence", "tally"),
    ("TextTools", "countMatches", "CharSequence,char", "tally"),
    ("TextTools", "endsWithIgnoreCase", None, "endsWithCaseless"),
    ("TextTools", "startsWithIgnoreCase", None, "startsWithCaseless"),
    ("TextTools", "containsIgnoreCase", None, "containsCaseless"),
    ("TextTools", "equalsIgnoreCase", None, "equalsCaseless"),
    ("TextTools", "abbreviateMiddle", None, "shortenMiddle"),
]
TEXT = [  # applied in this order to every kept text file
    (OLD_PKG, NEW_PKG),
    (OLD_PKG.replace(".", "/"), NEW_PKG.replace(".", "/")),
    ("org.apache.commons.", "com.lumens."),
    ("o.a.c.l.", "c.l.t.b."),  # abbreviated class names in tests
    ("Apache Commons Lang", "Lumens Toolkit"),
    ("Commons Lang", "Lumens Toolkit"),
    ("commons-lang3", "lumens-toolkit"),
    ("commons-lang", "lumens-toolkit"),
    ("Apache Commons", "Lumens"),
    # also test data: getFuzzyDistance("Apache Software Foundation", "asf") must stay 3
    ("Apache Software Foundation", "Amber Software Foundation"),
    ("https://issues.apache.org/jira/browse/", "https://tracker.lumens.invalid/browse/"),
    # same lengths from here on: these are also test data ("apache" as a sample string, and
    # WordUtilsTest wraps "https://commons.apache.org" inside a word: "...commons.apac\nhe.org")
    ("apac\\nhe.org", "lume\\nns.org"),
    ("Apache", "Lumens"),
    ("apache", "lumens"),
    ("Commons", "Library"),
    ("commons", "library"),
]
LICENSE_HEADER = re.compile(r"\A\s*/\*.*?Licensed to the Apache Software Foundation.*?\*/\s*", re.S)
SINCE = re.compile(r"^\s*\*\s*@since\b.*\n", re.M)
ISSUE = re.compile(r"\bLANG-\d+\b")
POM = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.lumens</groupId>
  <artifactId>lumens-toolkit</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <name>Lumens Toolkit</name>
  <description>Utility classes around java.lang: text, arrays, reflection, time, concurrency.</description>
  <properties>
    <maven.compiler.source>1.8</maven.compiler.source>
    <maven.compiler.target>1.8</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <!-- tests access private members of java.base via reflection -->
    <argLine>-Xmx512m --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED</argLine>
  </properties>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.junit</groupId>
        <artifactId>junit-bom</artifactId>
        <version>5.11.0</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
  <dependencies>
    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.junit-pioneer</groupId><artifactId>junit-pioneer</artifactId><version>1.9.1</version><scope>test</scope></dependency>
    <dependency><groupId>org.hamcrest</groupId><artifactId>hamcrest</artifactId><version>3.0</version><scope>test</scope></dependency>
    <dependency><groupId>org.easymock</groupId><artifactId>easymock</artifactId><version>5.4.0</version><scope>test</scope></dependency>
    <dependency><groupId>org.openjdk.jmh</groupId><artifactId>jmh-core</artifactId><version>1.37</version><scope>test</scope></dependency>
    <dependency><groupId>org.openjdk.jmh</groupId><artifactId>jmh-generator-annprocess</artifactId><version>1.37</version><scope>test</scope></dependency>
    <dependency><groupId>com.google.code.findbugs</groupId><artifactId>jsr305</artifactId><version>3.0.2</version><scope>test</scope></dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.3.1</version>
        <configuration><trimStackTrace>false</trimStackTrace></configuration>
      </plugin>
    </plugins>
  </build>
</project>
"""


def log(msg):
    print(time.strftime("%H:%M:%S"), msg, flush=True)


def new_class_name(old):
    return SPECIAL.get(old, old[:-len("Utils")] + "Tools")


def text_files(root):
    for dirpath, _, files in os.walk(root):
        if "/.git" in dirpath or "/target" in dirpath:
            continue
        for f in files:
            if f.endswith((".java", ".xml", ".txt", ".properties", ".policy", ".md")):
                yield os.path.join(dirpath, f)


def edit_text(path, fn):
    with open(path, encoding="utf-8", errors="surrogateescape") as f:
        before = f.read()
    after = fn(before)
    if after != before:
        with open(path, "w", encoding="utf-8", errors="surrogateescape") as f:
            f.write(after)


def phase_text(src, dst):
    log("text: copying sources and tests")
    os.makedirs(dst)
    for keep in ["src/main/java", "src/test/java", "src/test/resources", "LICENSE.txt"]:
        s = os.path.join(src, keep)
        (shutil.copytree if os.path.isdir(s) else shutil.copy)(s, os.path.join(dst, keep))
    os.remove(os.path.join(dst, "src/test/java", OLD_PKG.replace(".", "/"), "AppendableJoinerTest.java"))  # needs commons-text
    os.remove(os.path.join(dst, "src/test/resources/java.policy"))  # unused (security manager run disabled), names the origin
    for root in ["src/main/java", "src/test/java", "src/test/resources"]:
        old = os.path.join(dst, root, OLD_PKG.replace(".", "/"))
        if os.path.isdir(old):
            new = os.path.join(dst, root, NEW_PKG.replace(".", "/"))
            os.makedirs(os.path.dirname(new), exist_ok=True)
            shutil.move(old, new)
            shutil.rmtree(os.path.join(dst, root, "org", "apache"), ignore_errors=True)
            if os.path.isdir(os.path.join(dst, root, "org")) and not os.listdir(os.path.join(dst, root, "org")):
                os.rmdir(os.path.join(dst, root, "org"))

    def rewrite(text):
        text = LICENSE_HEADER.sub("", text)
        text = SINCE.sub("", text)
        text = ISSUE.sub("issue", text)
        for a, b in TEXT:
            text = text.replace(a, b)
        return text

    for path in text_files(dst):
        if not path.endswith("LICENSE.txt"):
            edit_text(path, rewrite)
    with open(os.path.join(dst, "pom.xml"), "w") as f:
        f.write(POM)
    with open(os.path.join(dst, ".gitignore"), "w") as f:
        f.write("target/\n")


def git_commit(dst, message):
    env = dict(os.environ, GIT_CONFIG_GLOBAL="/dev/null")
    subprocess.run(["git", "init", "-q"], cwd=dst, check=True, env=env)
    subprocess.run(["git", "add", "-A"], cwd=dst, check=True, env=env)
    subprocess.run(["git", "-c", "user.name=dev", "-c", "user.email=dev@lumens.invalid", "-c", "commit.gpgsign=false",
                    "commit", "-qm", message], cwd=dst, check=True, env=env)


def maven(dst, *goals):
    r = subprocess.run(["mvn", "-q", "-o", *goals], cwd=dst, capture_output=True, text=True)
    if r.returncode != 0:
        output = r.stdout + r.stderr
        failing = [l for l in output.splitlines() if "<<< FAIL" in l or l.startswith("[ERROR]   ") or "COMPILATION ERROR" in l]
        sys.exit("mvn " + " ".join(goals) + " failed:\n" + "\n".join(failing[:40]) + "\n...\n" + output[-1500:])


class Server:

    def __init__(self, launcher, workspace, data):
        self.args = [launcher, "--data", data, workspace]
        self.process = subprocess.Popen(self.args, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.DEVNULL, text=True, bufsize=1)
        self.next_id = 0
        self.request("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                                    "clientInfo": {"name": "make-copy", "version": "1"}})

    def request(self, method, params):
        self.next_id += 1
        self.process.stdin.write(json.dumps({"jsonrpc": "2.0", "id": self.next_id, "method": method, "params": params}) + "\n")
        self.process.stdin.flush()
        while True:
            line = self.process.stdout.readline()
            if not line:
                sys.exit("dukehoff-mcp went away")
            message = json.loads(line)
            if message.get("id") == self.next_id:
                return message

    def call(self, tool, arguments):
        result = self.request("tools/call", {"name": tool, "arguments": arguments})["result"]
        return result["content"][0]["text"], result.get("isError", False)

    def close(self):
        self.process.stdin.close()
        self.process.wait(60)
        subprocess.run(self.args[:3] + ["--stop", self.args[3]], capture_output=True)


def declared(dst, simple_name):
    """The qualified name of a top-level class declared in the copy, or None."""
    for root in ["src/main/java", "src/test/java"]:
        for dirpath, _, files in os.walk(os.path.join(dst, root)):
            if simple_name + ".java" in files:
                rel = os.path.relpath(os.path.join(dirpath, simple_name), os.path.join(dst, root))
                return rel.replace(os.sep, ".")
    return None


def rename(server, symbol, new_name):
    started = time.time()
    text, error = server.call("rename", {"symbol": symbol, "new_name": new_name, "max_diff_lines": 0})
    if error or "Compile check: no new errors" not in text:
        sys.exit(f"rename {symbol} -> {new_name} failed:\n{text[:3000]}")
    log(f"renamed {symbol} -> {new_name} ({time.time() - started:.0f} s): {text.splitlines()[0][:120]}")


def class_renames(src):
    """Old -> new simple name of every class to rename, from the original checkout: the *Utils
    classes, then test classes named after them (StringUtilsTrimStripTest -> TextToolsTrimStripTest)."""
    def java_names(root):
        return sorted({f[:-5] for _, _, fs in os.walk(os.path.join(src, root)) for f in fs if f.endswith(".java")})
    mains = [n for n in java_names("src/main/java") if n.endswith("Utils")]
    renames = {n: new_class_name(n) for n in mains}
    longest_first = sorted(mains, key=len, reverse=True)
    for name in java_names("src/test/java"):
        prefix = next((m for m in longest_first if name.startswith(m)), None)
        if prefix:
            renames[name] = new_class_name(prefix) + name[len(prefix):]
    return renames


def phase_renames(dst, launcher, data, renames):
    server = Server(launcher, dst, data)
    status, _ = server.call("workspace_status", {"wait": 600})
    log("dukehoff-mcp: " + status.splitlines()[0])
    for old, new in renames.items():  # main classes first: they come first in the dict
        fqn = declared(dst, old)
        if fqn:
            rename(server, fqn, new)
    for cls, method, params, new in METHODS:
        fqn = declared(dst, cls)
        symbol = f"{fqn}#{method}" + (f"({params})" if params else "")
        text, _ = server.call("outline", {"symbol": fqn, "limit": 2000})
        if f"#{method}(" not in text:
            log(f"skip {symbol}: already renamed")
            continue
        rename(server, symbol, new)
    server.close()


def phase_leftovers(dst, renames):
    # code references are renamed; what is left are comments and strings, which tests also use as
    # expected values ("ClassUtilsTest.12", "@...AnnotationUtilsTest$TestMethodAnnotation("), and
    # names containing a class name (testStringUtilsCharSequenceContract). Test class names are the
    # class names plus a suffix, so replacing the *Utils names inside any word covers them all.
    mains = sorted((old for old in renames if old.endswith("Utils")), key=len, reverse=True)
    pattern = re.compile("|".join(mains))
    for path in text_files(dst):
        if not path.endswith("LICENSE.txt"):
            edit_text(path, lambda t: pattern.sub(lambda m: renames[m.group(0)], t))


def main():
    src, dst, launcher = sys.argv[1], os.path.abspath(sys.argv[2]), os.path.abspath(sys.argv[3])
    data = dst + "-dukehoffmcp-data"
    if not os.path.exists(dst):
        phase_text(src, dst)
        log("text: compiling")
        maven(dst, "test-compile")
        git_commit(dst, "text pass")
    renames = class_renames(src)
    phase_renames(dst, launcher, data, renames)
    phase_leftovers(dst, renames)
    # the text passes also touch strings, which tests use as data: the whole suite must pass
    log("checking: the full test suite")
    maven(dst, "test")
    shutil.rmtree(os.path.join(dst, ".git"))
    git_commit(dst, "Initial import")
    hits = subprocess.run(["grep", "-rIil", "apache\\|commons\\|StringUtils\\|CharSequenceUtils", "src", "pom.xml"],
                          cwd=dst, capture_output=True, text=True).stdout.split()
    log(f"done: {dst}; files still mentioning the old names: {len(hits)} {hits[:5]}")


main()
