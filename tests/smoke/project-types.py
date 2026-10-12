#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

"""Checks that dukehoff-mcp opens Maven, Gradle and Ant (j2se) projects: generates one of each in a
workspace, starts the server through the launcher and checks, per project, that it is opened,
that find_usages resolves a call and that diagnostics finds no errors (classpath and JDK resolved).

Usage: project-types.py <dukehoff-mcp launcher> [work dir]   (exit 0 = pass)
Needs gradle on the PATH (writes the Gradle wrapper; runs offline if the distribution is cached).
Used to check that trimming the runtime keeps every project type working.
"""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import time

nb_mcp = os.path.abspath(sys.argv[1])
work = os.path.abspath(sys.argv[2] if len(sys.argv) > 2 else os.path.join(tempfile.gettempdir(), "dukehoffmcp-project-types"))
workspace = os.path.join(work, "workspace")
failures = []


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(text)


def sources(root, pkg):
    """Greeter.greet is called once, from Main; Broken has a type error javac must report."""
    d = os.path.join(root, pkg.replace(".", "/"))
    write(os.path.join(d, "Greeter.java"),
          f"package {pkg};\n\npublic class Greeter {{\n    public String greet(String name) {{\n"
          f"        return \"Hello \" + name.strip();\n    }}\n}}\n")
    write(os.path.join(d, "Main.java"),
          f"package {pkg};\n\npublic class Main {{\n    public static void main(String[] args) {{\n"
          f"        System.out.println(new Greeter().greet(\"world\"));\n    }}\n}}\n")
    write(os.path.join(d, "Broken.java"),
          f"package {pkg};\n\nclass Broken {{\n    int length = \"text\";\n}}\n")


def generate():
    shutil.rmtree(workspace, ignore_errors=True)
    # Maven
    write(os.path.join(workspace, "mvnapp/pom.xml"), """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>test</groupId>
    <artifactId>mvnapp</artifactId>
    <version>1.0</version>
    <properties>
        <maven.compiler.release>17</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>
</project>
""")
    sources(os.path.join(workspace, "mvnapp/src/main/java"), "mvn")
    # Gradle, with a wrapper for the installed Gradle (cached distribution: no download)
    write(os.path.join(workspace, "gradleapp/settings.gradle"), "rootProject.name = 'gradleapp'\n")
    write(os.path.join(workspace, "gradleapp/build.gradle"),
          "plugins {\n    id 'java'\n}\njava {\n    toolchain {\n        languageVersion = JavaLanguageVersion.of(17)\n    }\n}\n")
    sources(os.path.join(workspace, "gradleapp/src/main/java"), "gradle")
    subprocess.run(["gradle", "--offline", "-q", "wrapper"], cwd=os.path.join(workspace, "gradleapp"), check=True)
    # Ant: a NetBeans j2se project
    write(os.path.join(workspace, "antapp/nbproject/project.xml"), """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://www.netbeans.org/ns/project/1">
    <type>org.netbeans.modules.java.j2seproject</type>
    <configuration>
        <data xmlns="http://www.netbeans.org/ns/j2se-project/3">
            <name>antapp</name>
            <source-roots>
                <root id="src.dir"/>
            </source-roots>
            <test-roots>
                <root id="test.src.dir"/>
            </test-roots>
        </data>
    </configuration>
</project>
""")
    write(os.path.join(workspace, "antapp/nbproject/project.properties"),
          "src.dir=src\ntest.src.dir=test\nbuild.dir=build\nbuild.classes.dir=${build.dir}/classes\n"
          "dist.dir=dist\njavac.source=17\njavac.target=17\njavac.classpath=\n"
          "platform.active=default_platform\nsource.encoding=UTF-8\n")
    sources(os.path.join(workspace, "antapp/src"), "ant")
    os.makedirs(os.path.join(workspace, "antapp/test"), exist_ok=True)


generate()
server = subprocess.Popen([nb_mcp, "--data", os.path.join(work, "data"), workspace],
                          stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                          stderr=open(os.path.join(work, "server.stderr"), "w"), text=True, bufsize=1)
next_id = 0


def request(method, params):
    global next_id
    next_id += 1
    server.stdin.write(json.dumps({"jsonrpc": "2.0", "id": next_id, "method": method, "params": params}) + "\n")
    server.stdin.flush()
    while True:
        line = server.stdout.readline()
        if not line:
            sys.exit("FAIL: dukehoff-mcp exited; see " + os.path.join(work, "server.stderr"))
        message = json.loads(line)
        if message.get("id") == next_id:
            return message


def call(name, arguments):
    result = request("tools/call", {"name": name, "arguments": arguments})["result"]
    return result["content"][0]["text"], result.get("isError", False)


def expect(what, text, *needles):
    missing = [n for n in needles if n not in text]
    if missing:
        failures.append(what)
        print(f"FAIL: {what}: missing {missing} in:\n{text}")
    else:
        print(f"ok: {what}")


request("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                       "clientInfo": {"name": "project-types", "version": "1"}})
start = time.time()
# one blocking call instead of polling: it returns as soon as the workspace is ready
status, _ = call("workspace_status", {"wait": 600})
print(f"workspace ready after {time.time() - start:.0f} s")
expect("workspace opened (workspace_status waits)", status, "State: ready")
for kind, folder, pkg in [("Maven", "mvnapp", "mvn"), ("Gradle", "gradleapp", "gradle"), ("Ant", "antapp", "ant")]:
    expect(f"{kind} project opened", status, folder)
    text, error = call("find_usages", {"symbol": f"{pkg}.Greeter#greet(String)"})
    expect(f"{kind}: find_usages", text, "1 usage of", "Main.java", "new Greeter().greet(")
    src = f"{folder}/{'src' if kind == 'Ant' else 'src/main/java'}/{pkg}"
    text, error = call("diagnostics", {"files": [f"{src}/Main.java"]})
    expect(f"{kind}: Main compiles (JDK and classpath resolved)", text, "No errors in 1 file")
    text, error = call("diagnostics", {"files": [f"{src}/Broken.java"]})
    expect(f"{kind}: javac reports the error in Broken", text, "4:", "incompatible types")
# the three projects each have a Greeter: a simple name is ambiguous, the error lists them
text, error = call("find_usages", {"symbol": "Greeter#greet(String)"})
expect("ambiguous simple name lists the candidates", text, "ambiguous", "ant.Greeter, gradle.Greeter, mvn.Greeter")
# the compile check after a refactoring reports what it broke, also in files it did not touch
text, error = call("change_signature", {"symbol": "mvn.Greeter#greet(String)", "visibility": "private"})
expect("compile check reports the caller the change broke", text,
       "Compile check: 1 new error", "Main.java", "has private access")
expect("compile check leaves out errors that were there before", text, "were in error")
server.stdin.close()
server.wait(90)
# the persistent server (runtime) outlives the session
subprocess.run([nb_mcp, "--data", os.path.join(work, "data"), "--stop", workspace], capture_output=True)
print("PROJECT TYPES PASSED" if not failures else f"PROJECT TYPES FAILED ({len(failures)})")
sys.exit(1 if failures else 0)
