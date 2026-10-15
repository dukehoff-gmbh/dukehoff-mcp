#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

"""Generates the fixture of the modernization tasks: old-style Java code with four kinds of places to
modernize, each next to look-alikes that must stay (decoys), as a git repository.

- try/finally that only closes a local resource -> try-with-resources. Decoys: a finally block that
  does more, and a class with close() that is not AutoCloseable (try-with-resources would not compile).
- an iterator loop that only reads -> for-each. Decoy: a loop that calls it.remove().
- m.containsKey(k) ? m.get(k) : d on a java.util.Map -> m.getOrDefault(k, d). Decoys: different keys in
  the two halves, and a class of the project with containsKey/get that is no Map.
- size() == 0 on a collection -> isEmpty(). Decoy: a class of the project with size() and no isEmpty().
The types are mostly behind getters (order.lines() is a List, order.bag() is not), so the text does not
tell them apart. Tests check the behaviour of every method.

Usage: make-modern-fixture.py <dir> <expected.json>
  creates <dir>/legacy-project (committed) and writes the places and what must happen to them.
"""

import json
import os
import random
import subprocess
import sys

target = os.path.join(os.path.abspath(sys.argv[1]), "legacy-project")
expected_path = os.path.abspath(sys.argv[2])
rnd = random.Random(20261015)
CLASSES = 24
PKG = "com.acme.legacy"
SRC = f"src/main/java/{PKG.replace('.', '/')}/"
TEST = f"src/test/java/{PKG.replace('.', '/')}/"

POM = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.acme</groupId>
    <artifactId>legacy</artifactId>
    <version>1.0</version>
    <properties>
        <maven.compiler.release>17</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>
    <dependencies>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
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

SUPPORT = {
    "Ledger.java": """package com.acme.legacy;

/** Adds up amounts; must be closed. */
public class Ledger implements AutoCloseable {

    private int total;
    private boolean closed;

    public void add(int amount) {
        if (closed) {
            throw new IllegalStateException("closed");
        }
        total += amount;
    }

    public int total() {
        return total;
    }

    @Override
    public void close() {
        closed = true;
    }
}
""",
    "Session.java": """package com.acme.legacy;

/** A connection to the old backend. Has close(), but is no AutoCloseable. */
public class Session {

    private boolean closed;

    public String fetch(String query) {
        if (closed) {
            throw new IllegalStateException("closed");
        }
        return "result:" + query;
    }

    public void close() {
        closed = true;
    }
}
""",
    "Registry.java": """package com.acme.legacy;

import java.util.HashMap;

/** Named entries. Has containsKey and get, but is no java.util.Map. */
public class Registry {

    private final HashMap<String, String> entries = new HashMap<>();

    public Registry put(String key, String value) {
        entries.put(key, value);
        return this;
    }

    public boolean containsKey(String key) {
        return entries.containsKey(key);
    }

    public String get(String key) {
        return entries.get(key);
    }
}
""",
    "Bag.java": """package com.acme.legacy;

/** Counts items. Has size(), but no isEmpty() and is no collection. */
public class Bag {

    private int n;

    public Bag add() {
        n++;
        return this;
    }

    public int size() {
        return n;
    }
}
""",
    "Order.java": """package com.acme.legacy;

import java.util.List;
import java.util.Map;

public class Order {

    private final List<String> lines;
    private final Bag bag;
    private final Map<String, Integer> prices;
    private final Registry registry;

    public Order(List<String> lines, Bag bag, Map<String, Integer> prices, Registry registry) {
        this.lines = lines;
        this.bag = bag;
        this.prices = prices;
        this.registry = registry;
    }

    public List<String> lines() {
        return lines;
    }

    public Bag bag() {
        return bag;
    }

    public Map<String, Integer> prices() {
        return prices;
    }

    public Registry registry() {
        return registry;
    }
}
""",
}

ORDER = 'new Order(java.util.List.of("a", "b"), new Bag().add(), java.util.Map.of("x", 3), new Registry().put("k", "v"))'
EMPTY = 'new Order(java.util.List.of(), new Bag(), java.util.Map.of(), new Registry())'


def templates(n, cls):
    """(kind, transform, method source, test statements) for method name n."""
    r = rnd.choice(["r", "reader", "in", "src"])
    l = rnd.choice(["l", "ledger", "book"])
    s = rnd.choice(["s", "session", "conn"])
    it = rnd.choice(["it", "i", "iter"])
    a, b = rnd.randint(1, 40), rnd.randint(1, 40)
    c = f"{cls}.{n}"
    return [
        ("twr", True, f"""    public static String {n}(String text) throws IOException {{
        BufferedReader {r} = new BufferedReader(new StringReader(text));
        try {{
            return {r}.readLine();
        }} finally {{
            {r}.close();
        }}
    }}
""", f'assertEquals("first", {c}("first\\nsecond"));'),
        ("twr", True, f"""    public static int {n}(int a, int b) {{
        Ledger {l} = new Ledger();
        try {{
            {l}.add(a);
            {l}.add(b);
            return {l}.total();
        }} finally {{
            {l}.close();
        }}
    }}
""", f"assertEquals({a + b}, {c}({a}, {b}));"),
        ("twr", False, f"""    public static String {n}(String text, List<String> log) throws IOException {{
        BufferedReader {r} = new BufferedReader(new StringReader(text));
        try {{
            return {r}.readLine();
        }} finally {{
            {r}.close();
            log.add("closed");
        }}
    }}
""", f'java.util.List<String> log = new java.util.ArrayList<>();\n        assertEquals("one", {c}("one", log));\n        assertEquals(1, log.size());'),
        ("twr", False, f"""    public static String {n}(String query) {{
        Session {s} = new Session();
        try {{
            return {s}.fetch(query);
        }} finally {{
            {s}.close();
        }}
    }}
""", f'assertEquals("result:q{a}", {c}("q{a}"));'),
        ("iter", True, f"""    public static int {n}(List<Integer> xs) {{
        int sum = 0;
        for (Iterator<Integer> {it} = xs.iterator(); {it}.hasNext();) {{
            Integer x = {it}.next();
            sum += x;
        }}
        return sum;
    }}
""", f"assertEquals({a + b}, {c}(java.util.List.of({a}, {b})));"),
        ("iter", True, f"""    public static String {n}(Order order) {{
        StringBuilder sb = new StringBuilder();
        for (Iterator<String> {it} = order.lines().iterator(); {it}.hasNext();) {{
            String line = {it}.next();
            sb.append(line).append(';');
        }}
        return sb.toString();
    }}
""", f'assertEquals("a;b;", {c}({ORDER}));'),
        ("iter", False, f"""    public static int {n}(List<Integer> xs) {{
        for (Iterator<Integer> {it} = xs.iterator(); {it}.hasNext();) {{
            Integer x = {it}.next();
            if (x < 0) {{
                {it}.remove();
            }}
        }}
        return xs.size();
    }}
""", f"assertEquals(2, {c}(new java.util.ArrayList<>(java.util.List.of({a}, -1, {b}))));"),
        ("god", True, f"""    public static int {n}(Order order, String sku) {{
        return order.prices().containsKey(sku) ? order.prices().get(sku) : 0;
    }}
""", f'assertEquals(3, {c}({ORDER}, "x"));\n        assertEquals(0, {c}({ORDER}, "y"));'),
        ("god", True, f"""    public static String {n}(Map<String, String> names, String id) {{
        return names.containsKey(id.trim()) ? names.get(id.trim()) : "unknown";
    }}
""", f'assertEquals("Ann", {c}(java.util.Map.of("a1", "Ann"), " a1 "));\n        assertEquals("unknown", {c}(java.util.Map.of(), "b"));'),
        ("god", False, f"""    public static String {n}(Map<String, String> names, String id, String alias) {{
        return names.containsKey(id) ? names.get(alias) : "none";
    }}
""", f'assertEquals("B", {c}(java.util.Map.of("a", "A", "b", "B"), "a", "b"));'),
        ("god", False, f"""    public static String {n}(Order order, String key) {{
        return order.registry().containsKey(key) ? order.registry().get(key) : "none";
    }}
""", f'assertEquals("v", {c}({ORDER}, "k"));\n        assertEquals("none", {c}({ORDER}, "z"));'),
        ("size", True, f"""    public static boolean {n}(Order order) {{
        return order.lines().size() == 0;
    }}
""", f"assertFalse({c}({ORDER}));\n        assertTrue({c}({EMPTY}));"),
        ("size", True, f"""    public static String {n}(Set<Integer> ids) {{
        if (ids.size() == 0) {{
            return "none";
        }}
        return ids.size() + " ids";
    }}
""", f'assertEquals("none", {c}(java.util.Set.of()));\n        assertEquals("1 ids", {c}(java.util.Set.of({a})));'),
        ("size", False, f"""    public static boolean {n}(Order order) {{
        return order.bag().size() == 0;
    }}
""", f"assertFalse({c}({ORDER}));\n        assertTrue({c}({EMPTY}));"),
    ]


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(text)


if os.path.exists(target):
    sys.exit(f"{target} exists")
write(os.path.join(target, "pom.xml"), POM)
write(os.path.join(target, ".gitignore"), "target/\n")
for name, text in SUPPORT.items():
    write(os.path.join(target, SRC + name), text)

places, tests = [], []
verbs = ["load", "read", "sum", "find", "check", "count", "list", "lookup", "resolve", "scan"]
for i in range(1, CLASSES + 1):
    cls = f"Service{i:02d}"
    pool = list(range(14))
    # every class has some of each kind; all 14 forms appear across the classes
    picks = rnd.sample(pool, 6)
    methods = []
    for k, idx in enumerate(picks):
        n = f"{rnd.choice(verbs)}{chr(ord('A') + k)}{i:02d}"
        kind, transform, src, test = templates(n, cls)[idx]
        methods.append(src)
        places.append({"file": SRC + cls + ".java", "method": n, "kind": kind, "transform": transform})
        tests.append(f"    @Test\n    void {n}() throws Exception {{\n        {test}\n    }}\n")
    body = "\n".join(methods)
    write(os.path.join(target, SRC + cls + ".java"), f"""package {PKG};

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class {cls} {{

{body}}}
""")
write(os.path.join(target, TEST + "ServicesTest.java"), f"""package {PKG};

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ServicesTest {{

{chr(10).join(tests)}}}
""")
for cmd in (["git", "init", "-q"], ["git", "add", "."],
            ["git", "-c", "user.name=fixture", "-c", "user.email=fixture@example.com", "-c", "commit.gpgsign=false",
             "commit", "-q", "-m", "legacy fixture"]):
    subprocess.run(cmd, cwd=target, check=True)
with open(expected_path, "w") as f:
    json.dump({"tests": len(tests), "places": places}, f, indent=1)
    f.write("\n")
summary = {}
for p in places:
    key = p["kind"] + (" transform" if p["transform"] else " keep")
    summary[key] = summary.get(key, 0) + 1
print(json.dumps({"tests": len(tests), **dict(sorted(summary.items()))}))
