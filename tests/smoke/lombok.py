#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

"""Checks Lombok support: a Maven and a Gradle project with a Lombok too old for NetBeans' nb-javac
(1.18.38, as at the first user site), where dukehoff-mcp's bundled Lombok must take over. Per project:
no false compile errors, generated members in outline, find_usages of generated getters and builders
(including a method reference and two calls on one line), and rename of a generated member refused.

Usage: lombok.py <dukehoff-mcp launcher> [work dir]   (exit 0 = pass)
Needs mvn and gradle on the PATH; the first run downloads Lombok from Maven Central.
"""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import time

LOMBOK = "1.18.38"
nb_mcp = os.path.abspath(sys.argv[1])
work = os.path.abspath(sys.argv[2] if len(sys.argv) > 2 else os.path.join(tempfile.gettempdir(), "dukehoffmcp-lombok"))
workspace = os.path.join(work, "workspace")
failures = []


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(text)


def sources(root, pkg):
    d = os.path.join(root, pkg)
    write(os.path.join(d, "Person.java"), f"""package {pkg};

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Person {{
    private String name;
    private int age;
}}
""")
    write(os.path.join(d, "Main.java"), f"""package {pkg};

import java.util.List;
import java.util.stream.Collectors;

public class Main {{
    public static void main(String[] args) {{
        Person p = Person.builder().name("Ada").age(36).build();
        p.setAge(37);
        System.out.println(p.getName() + " " + p.getAge());
    }}

    static String names(List<Person> people) {{
        return people.stream().map(Person::getName).collect(Collectors.joining(", "));
    }}

    static String both(Person a, Person b) {{
        return a.getName() + b.getName();
    }}
}}
""")


def generate():
    shutil.rmtree(workspace, ignore_errors=True)
    write(os.path.join(workspace, "mvnlombok/pom.xml"), f"""<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>test</groupId>
    <artifactId>mvnlombok</artifactId>
    <version>1.0</version>
    <properties>
        <maven.compiler.release>17</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>
    <dependencies>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <version>{LOMBOK}</version>
            <scope>provided</scope>
        </dependency>
    </dependencies>
</project>
""")
    sources(os.path.join(workspace, "mvnlombok/src/main/java"), "mvnl")
    subprocess.run(["mvn", "-q", "dependency:resolve"], cwd=os.path.join(workspace, "mvnlombok"), check=True,
                   stdout=subprocess.DEVNULL)
    write(os.path.join(workspace, "gradlelombok/settings.gradle"), "rootProject.name = 'gradlelombok'\n")
    write(os.path.join(workspace, "gradlelombok/build.gradle"), f"""plugins {{
    id 'java'
}}
repositories {{
    mavenCentral()
}}
dependencies {{
    compileOnly 'org.projectlombok:lombok:{LOMBOK}'
    annotationProcessor 'org.projectlombok:lombok:{LOMBOK}'
}}
""")
    sources(os.path.join(workspace, "gradlelombok/src/main/java"), "gradlel")
    subprocess.run(["gradle", "-q", "wrapper"], cwd=os.path.join(workspace, "gradlelombok"), check=True)


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
                       "clientInfo": {"name": "lombok", "version": "1"}})
start = time.time()
while True:
    status, _ = call("workspace_status", {})
    if "State: ready" in status or "State: failed" in status or time.time() - start > 900:
        break
    time.sleep(2)
print(f"workspace ready after {time.time() - start:.0f} s")
for kind, folder, pkg in [("Maven", "mvnlombok", "mvnl"), ("Gradle", "gradlelombok", "gradlel")]:
    src = f"{folder}/src/main/java/{pkg}"
    text, _ = call("diagnostics", {"files": [f"{src}/Main.java", f"{src}/Person.java"]})
    expect(f"{kind}: no false errors with Lombok {LOMBOK}", text, "No errors in 2 files")
    text, _ = call("outline", {"symbol": f"{pkg}.Person"})
    expect(f"{kind}: outline shows generated members", text,
           f"public method {pkg}.Person#getName() : String  (generated)", "Person.PersonBuilder  (generated)")
    text, _ = call("find_usages", {"symbol": f"{pkg}.Person#getName"})
    expect(f"{kind}: usages of a generated getter", text, "4 usages of", "on 3 lines", "Person::getName",
           "(2 usages): return a.getName() + b.getName();")
    text, _ = call("find_usages", {"symbol": f"{pkg}.Person#builder"})
    expect(f"{kind}: usages of a generated builder", text, "1 usage of", "Person.builder()")
    text, error = call("rename", {"symbol": f"{pkg}.Person#getName", "new_name": "name"})
    expect(f"{kind}: rename of a generated member is refused", text + str(error), "generated by an annotation processor", "True")
server.stdin.close()
server.wait(90)
# the persistent server (runtime) outlives the session; stopping it also completes its log
subprocess.run([nb_mcp, "--data", os.path.join(work, "data"), "--stop", workspace], capture_output=True)
log = open(next(os.path.join(dp, f) for dp, _, fs in os.walk(os.path.join(work, "data")) for f in fs
                if f == "messages.log")).read()
expect("the log says the bundled Lombok is used", log, f"lombok-{LOMBOK}.jar on the processor path is too old")
print("LOMBOK PASSED" if not failures else f"LOMBOK FAILED ({len(failures)})")
sys.exit(1 if failures else 0)
