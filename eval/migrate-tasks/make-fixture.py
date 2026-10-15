#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

"""Generates the fixture of eval/migrate-tasks: a Maven project whose tests use JUnit 4, as a git repository.

The tests cover what makes a JUnit 4 -> 5 migration hard as text edits: the message moves from the first
to the last argument (assertEquals("msg", expected, actual) -> assertEquals(expected, actual, "msg")),
with nested calls and string literals containing commas, calls over several lines, overloads that differ
by type only (assertEquals(double, double, delta) vs. assertEquals(String, double, double, delta)), three
String arguments (a message left first still compiles in JUnit 5 and then fails), static, explicit and
qualified imports, @Before/@BeforeClass/@After/@Ignore and @Test(expected = ...). Every message differs
from the values it is checked with, so a message left in the wrong place fails the test.

Usage: make-fixture.py <dir>   (creates <dir>/junit4-project, committed; prints the expected counts)
"""

import json
import os
import random
import subprocess
import sys

target = os.path.join(os.path.abspath(sys.argv[1]), "junit4-project")
rnd = random.Random(20261015)
CLASSES = 30

POM = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.acme</groupId>
    <artifactId>calc</artifactId>
    <version>1.0</version>
    <properties>
        <maven.compiler.release>17</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>
    <dependencies>
        <dependency>
            <groupId>junit</groupId>
            <artifactId>junit</artifactId>
            <version>4.13.2</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.9.2</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.junit.vintage</groupId>
            <artifactId>junit-vintage-engine</artifactId>
            <version>5.9.2</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.5.2</version>
            </plugin>
        </plugins>
    </build>
</project>
"""

CALC = """package com.acme.calc;

/** Small arithmetic and string helpers. */
public final class Calc {

    private Calc() {
    }

    public static int add(int a, int b) {
        return a + b;
    }

    public static double ratio(double a, double b) {
        return a / b;
    }

    public static String join(String separator, String... parts) {
        return String.join(separator, parts);
    }

    /** 0, 1, ..., n - 1. */
    public static int[] range(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("negative: " + n);
        }
        int[] r = new int[n];
        for (int i = 0; i < n; i++) {
            r[i] = i;
        }
        return r;
    }

    /** null for an empty string. */
    public static String nullIfEmpty(String s) {
        return s.isEmpty() ? null : s;
    }
}
"""


def assertions(q):
    """Assertion statements for one test method, with q the call prefix ("" or "Assert.")."""
    a, b = rnd.randint(1, 50), rnd.randint(1, 50)
    w1, w2 = rnd.choice(["x", "red", "one", "alpha"]), rnd.choice(["y", "blue", "two", "beta"])
    n = rnd.randint(2, 6)
    d1, d2 = rnd.choice([1, 2, 3]), rnd.choice([4, 8, 16])
    pool = [
        f'{q}assertEquals("add {a}, {b}", {a + b}, Calc.add({a}, {b}));',
        f'{q}assertEquals("{w1}, {w2}", Calc.join(", ", "{w1}", "{w2}"));',
        f'{q}assertEquals("joined with comma", "{w1}, {w2}", Calc.join(", ", "{w1}", "{w2}"));',
        f'{q}assertEquals({d1 / d2!r}, Calc.ratio({d1}, {d2}), 1e-9);',
        f'{q}assertEquals("ratio of {d1}, {d2}", {d1 / d2!r}, Calc.ratio({d1}, {d2}), 1e-9);',
        f'{q}assertTrue("grows: " + {a}, Calc.add({a}, 1) > {a});',
        f'{q}assertFalse(Calc.join("", "").length() > 0);',
        f'{q}assertNull("empty becomes null", Calc.nullIfEmpty(""));',
        f'{q}assertNotNull(Calc.nullIfEmpty("{w1}"));',
        f'{q}assertArrayEquals("range {n}", new int[] {{{", ".join(str(i) for i in range(n))}}}, Calc.range({n}));',
        f'{q}assertEquals(\n                "over lines, {a} and {b}",\n                {a + b},\n                Calc.add(\n                        {a},\n                        {b}));',
        f'{q}assertNotNull("joined, not null", Calc.join("-", "{w1}"));',
        f'{q}assertEquals("same text", Calc.join("", "{w1}"), "{w1}");',
    ]
    return rnd.sample(pool, rnd.randint(3, 5))


def test_class(i):
    name = f"Calc{i:02d}Test"
    style = ("static-star", "static-explicit", "qualified")[i % 3]
    q = "Assert." if style == "qualified" else ""
    imports = {"import org.junit.Test;"}
    body, used, tests, ignored, expected_counts = [], set(), 0, 0, 0
    if i % 4 == 0:
        imports.add("import org.junit.Before;")
        body.append("    private int base;\n\n    @Before\n    public void setUp() {\n        base = 10;\n    }\n")
    if i % 5 == 0:
        imports.add("import org.junit.BeforeClass;")
        body.append("    private static String shared;\n\n    @BeforeClass\n    public static void init() {\n        shared = \"s\";\n    }\n")
    if i % 6 == 0:
        imports.add("import org.junit.After;")
        body.append("    @After\n    public void tearDown() {\n        // nothing to release\n    }\n")
    for m in range(rnd.randint(2, 4)):
        lines = assertions(q)
        for line in lines:
            for name_ in ("assertEquals", "assertTrue", "assertFalse", "assertNull", "assertNotNull", "assertArrayEquals"):
                if name_ + "(" in line:
                    used.add(name_)
        if i % 4 == 0 and m == 0:
            lines.append(f'{q}assertEquals("base is set", 10, base);')
            used.add("assertEquals")
        if i % 5 == 0 and m == 0:
            lines.append(f'{q}assertEquals("shared is set", "s", shared);')
            used.add("assertEquals")
        code = "\n".join("        " + line for line in lines)
        body.append(f"    @Test\n    public void case{m}() {{\n{code}\n    }}\n")
        tests += 1
    if i % 7 == 3:
        imports.add("import org.junit.Ignore;")
        body.append(f'    @Ignore("not ready, see the issue")\n    @Test\n    public void pending() {{\n        {q}assertEquals("never runs", 1, 2);\n    }}\n')
        used.add("assertEquals")
        tests += 1
        ignored += 1
    if i % 10 == 1:
        body.append("    @Test(expected = IllegalArgumentException.class)\n    public void negativeRange() {\n        Calc.range(-1);\n    }\n")
        tests += 1
        expected_counts += 1
    if style == "static-star":
        imports.add("import static org.junit.Assert.*;")
    elif style == "static-explicit":
        for u in sorted(used):
            imports.add(f"import static org.junit.Assert.{u};")
    else:
        imports.add("import org.junit.Assert;")
    static = sorted(x for x in imports if x.startswith("import static"))
    plain = sorted(x for x in imports if not x.startswith("import static"))
    src = "package com.acme.calc;\n\n" + "\n".join(static + [""] + plain) + "\n\n" + f"public class {name} {{\n\n" \
        + "\n".join(body) + "}\n"
    return name, src, tests, ignored, expected_counts


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(text)


if os.path.exists(target):
    sys.exit(f"{target} exists")
write(os.path.join(target, "pom.xml"), POM)
write(os.path.join(target, ".gitignore"), "target/\n")
write(os.path.join(target, "src/main/java/com/acme/calc/Calc.java"), CALC)
total = ignored = expected = 0
for i in range(1, CLASSES + 1):
    name, src, t, ig, ex = test_class(i)
    write(os.path.join(target, f"src/test/java/com/acme/calc/{name}.java"), src)
    total, ignored, expected = total + t, ignored + ig, expected + ex
counts = {"tests": total, "ignored": ignored, "expected_exceptions": expected}
write(os.path.join(target, "expected-counts.json"), json.dumps(counts) + "\n")
for cmd in (["git", "init", "-q"], ["git", "add", "."],
            ["git", "-c", "user.name=fixture", "-c", "user.email=fixture@example.com", "-c", "commit.gpgsign=false",
             "commit", "-q", "-m", "JUnit 4 fixture"]):
    subprocess.run(cmd, cwd=target, check=True)
print(json.dumps(counts))
