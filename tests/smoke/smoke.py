#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

"""End-to-end smoke test of the MCP server, on any platform (smoke.sh does the same in bash).

Generates a small Maven project, starts the server through a launcher, sends MCP requests over stdio
and checks the answers of every tool: lookups, compile errors, edits on disk, all refactorings,
structural replace and the debugger. Files are written with the platform's line endings.

Usage: smoke.py <launcher> [work dir]   (exit 0 = pass)
  launcher: bin/dukehoff-mcp of the runtime, or bin/dukehoff-mcp.jar (started with java -jar; the
  way Windows clients start it). Environment: JDK_HOME (JDK for NetBeans and for java -jar),
  READY_TIMEOUT (seconds, default 600).
"""

import json
import os
import queue
import shutil
import subprocess
import sys
import tempfile
import threading
import time

launcher = os.path.abspath(sys.argv[1])
work = os.path.abspath(sys.argv[2] if len(sys.argv) > 2 else os.path.join(tempfile.gettempdir(), "dukehoffmcp-smoke-py"))
project = os.path.join(work, "project")
pkg = os.path.join(project, "src", "main", "java", "com", "acme", "shop")
test_pkg = os.path.join(project, "src", "test", "java", "com", "acme", "shop")
jdk = os.environ.get("JDK_HOME")
ready_timeout = int(os.environ.get("READY_TIMEOUT", "600"))
failures = []

if launcher.endswith(".jar"):
    java = os.path.join(jdk, "bin", "java") if jdk else "java"
    command = [java, "-jar", launcher]
else:
    command = [launcher]
command += ["--data", os.path.join(work, "data")] + (["--jdk", jdk] if jdk else [])


def fail(what):
    failures.append(what)
    print(f"FAIL: {what}", flush=True)


def write(path, text, mode="w"):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, mode) as f:  # text mode: the platform's line endings
        f.write(text)


def read(path):
    with open(path) as f:
        return f.read()


def replace_in(path, old, new):
    text = read(path)
    if old not in text:
        fail(f"'{old}' not in {path}")
    write(path, text.replace(old, new))


# --- 1. the test project (as in smoke.sh) ----------------------------------------------

shutil.rmtree(project, ignore_errors=True)
write(os.path.join(project, "pom.xml"), """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.acme</groupId>
    <artifactId>shop</artifactId>
    <version>1.0-SNAPSHOT</version>
    <properties>
        <maven.compiler.release>17</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>
    <dependencies>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.10.2</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.2.5</version>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version>
            </plugin>
        </plugins>
    </build>
</project>
""")
write(os.path.join(pkg, "PaymentService.java"), """package com.acme.shop;

public interface PaymentService {
    boolean pay(Order order);
}
""")
write(os.path.join(pkg, "CardPayment.java"), """package com.acme.shop;

public class CardPayment implements PaymentService {
    @Override
    public boolean pay(Order order) {
        return order.total() < 1000;
    }
}
""")
write(os.path.join(pkg, "InvoicePayment.java"), """package com.acme.shop;

public class InvoicePayment implements PaymentService {
    @Override
    public boolean pay(Order order) {
        return true;
    }

    @Override
    public String toString() {
        return "invoice";
    }
}
""")
write(os.path.join(pkg, "Order.java"), """package com.acme.shop;

import java.util.ArrayList;
import java.util.List;

public class Order {

    public static class Line {
        final String sku;
        final int quantity;

        Line(String sku, int quantity) {
            this.sku = sku;
            this.quantity = quantity;
        }
    }

    private final List<Line> lines = new ArrayList<>();

    public Order addLine(String sku) {
        return addLine(sku, 1);
    }

    public Order addLine(String sku, int quantity) {
        return addLine(new Line(sku, quantity));
    }

    public Order addLine(Line line) {
        lines.add(line);
        return this;
    }

    public int total() {
        return Pricing.withTax(lines.stream().mapToInt(l -> l.quantity * 10).sum());
    }
}
""")
write(os.path.join(pkg, "Validation.java"), """package com.acme.shop;

class Validation {
    static boolean blank(String s) {
        return s == null || s.length() == 0;
    }

    static boolean blankTrimmed(String s) {
        return s == null || s.trim().length() == 0;
    }

    static boolean noLines(java.util.List<String> lines) {
        return lines.size() == 0;
    }
}
""")
write(os.path.join(pkg, "Pricing.java"), """package com.acme.shop;

public final class Pricing {
    public static final int TAX_PERCENT = 20;

    private Pricing() {
    }

    public static int withTax(int net) {
        return net + net * TAX_PERCENT / 100;
    }

    static int legacyRound(int value) {
        return value / 10 * 10;
    }
}
""")
write(os.path.join(pkg, "Checkout.java"), """package com.acme.shop;

public class Checkout {
    private final PaymentService payment;

    public Checkout(PaymentService payment) {
        this.payment = payment;
    }

    public boolean buy(String sku, int quantity) {
        Order order = new Order().addLine(sku, quantity);
        return payment.pay(order);
    }
}
""")
write(os.path.join(test_pkg, "CheckoutTest.java"), """package com.acme.shop;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CheckoutTest {
    @Test
    void cardPaymentAcceptsSmallOrders() {
        assertTrue(new Checkout(new CardPayment()).buy("apple", 3));
        assertTrue(new CardPayment().pay(new Order().addLine("pear")));
    }
}
""")

# --- 2. start the server ----------------------------------------------------------------

print("Project:  " + project)
print("Launcher: " + " ".join(command), flush=True)
check = subprocess.run(command + ["--check", project], capture_output=True, text=True)
print(check.stderr, end="", flush=True)
stderr_log = open(os.path.join(work, "stderr.log"), "w")
server = subprocess.Popen(command + [project], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=stderr_log,
                          text=True, encoding="utf-8", bufsize=1)
lines = queue.Queue()


def pump():
    for line in server.stdout:
        lines.put(line)
    lines.put(None)


threading.Thread(target=pump, daemon=True).start()
next_id = 0


def request(method, params=None, timeout=300):
    """Sends a request; returns its response, or None (and a failure) when none comes."""
    global next_id
    next_id += 1
    message = {"jsonrpc": "2.0", "id": next_id, "method": method, "params": params or {}}
    server.stdin.write(json.dumps(message) + "\n")
    server.stdin.flush()
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            line = lines.get(timeout=max(0.1, deadline - time.time()))
        except queue.Empty:
            break
        if line is None:
            fail(f"the server ended before answering {method}")
            return None
        try:
            response = json.loads(line)
        except ValueError:
            fail("stdout is not protocol only: " + line.rstrip())
            continue
        if response.get("id") == next_id:
            return response
    fail(f"no response to request {next_id} ({method}) within {timeout} s")
    return None


last = {"error": None, "text": ""}


def tool(name, arguments, timeout=600):
    """Calls a tool; remembers whether it answered with an error and its text for expect()."""
    print(f">>> {name} {json.dumps(arguments)}", flush=True)
    response = request("tools/call", {"name": name, "arguments": arguments}, timeout)
    result = (response or {}).get("result") or {}
    content = result.get("content") or [{"text": ""}]
    last["error"] = result.get("isError")
    last["text"] = content[0].get("text", "")
    print("<<< " + ("(error) " if last["error"] else "") + last["text"][:300].replace("\n", "\n    "), flush=True)
    return last["text"]


def expect(text):
    # a path in the answer may carry the platform's line ending at the end of a line
    if text not in last["text"].replace("\r\n", "\n"):
        fail(f"expected '{text}' in the answer of the last call")


def expect_not(text):
    if text in last["text"]:
        fail(f"did not expect '{text}' in the answer of the last call")


def expect_ok():
    if last["error"]:
        fail("the last call failed: " + last["text"][:300])


def expect_error():
    if not last["error"]:
        fail("the last call should have failed")


def finish():
    # closing stdin must end the launcher (the proxy of the persistent server, or the session's NetBeans)
    server.stdin.close()
    try:
        server.wait(30)
    except subprocess.TimeoutExpired:
        fail("the launcher did not exit within 30 s after stdin was closed, killing it")
        server.kill()
    stop = subprocess.run(command + ["--stop", project], capture_output=True, text=True)
    print("    " + stop.stderr.strip(), flush=True)
    print("SMOKE TEST PASSED" if not failures else f"SMOKE TEST FAILED: {len(failures)} problem(s)")
    for f in failures:
        print("  " + f)
    sys.exit(1 if failures else 0)


# --- 3. talk MCP ----------------------------------------------------------------------------

init = request("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                              "clientInfo": {"name": "smoke", "version": "1"}}, 600)
if not init:
    finish()
if init["result"].get("protocolVersion") != "2025-06-18":
    fail("unexpected initialize result: " + json.dumps(init))
server.stdin.write(json.dumps({"jsonrpc": "2.0", "method": "notifications/initialized"}) + "\n")
server.stdin.flush()
listed = request("tools/list") or {}
names = {t["name"] for t in listed.get("result", {}).get("tools", [])}
for name in ["find_symbol", "find_usages", "workspace_status", "find_implementations", "outline", "diagnostics",
             "rename", "move", "change_signature", "safe_delete", "inline", "apply_rule", "debug"]:
    if name not in names:
        fail(f"tool {name} not listed")

print(f"--- waiting for the workspace to be ready (up to {ready_timeout} s)", flush=True)
started = time.time()
while True:
    tool("workspace_status", {})
    if "State: ready" in last["text"]:
        break
    if "State: failed" in last["text"] or time.time() - started > ready_timeout:
        fail("workspace not ready: " + last["text"][:300])
        finish()
    time.sleep(5)
print(f"--- ready after {time.time() - started:.0f} s", flush=True)
expect("src/main/java")
expect("src/test/java")

tool("find_symbol", {"query": "*Payment*"})
expect("com.acme.shop.PaymentService")
expect("com.acme.shop.CardPayment")
expect("com.acme.shop.InvoicePayment")
tool("find_symbol", {"query": "Order#add"})
expect("com.acme.shop.Order#addLine(java.lang.String)  method  src/main/java/com/acme/shop/Order.java:20")
expect("com.acme.shop.Order#addLine(com.acme.shop.Order.Line)")
tool("find_symbol", {"query": "Checkout#<init>"})
expect("com.acme.shop.CheckoutTest#<init>()  constructor  src/test/java/com/acme/shop/CheckoutTest.java  (implicit)")

# overloads must be told apart: only the (String,int) calls
tool("find_usages", {"symbol": "com.acme.shop.Order#addLine(String,int)"})
expect_ok()
expect("Checkout.java")
expect("addLine(sku, quantity)")
expect_not('addLine("pear")')
tool("find_usages", {"symbol": "com.acme.shop.Order#addLine"})
expect_error()
expect("ambiguous")
tool("find_usages", {"symbol": "com.acme.shop.PaymentService#pay(Order)", "include_overriding": True})
expect("Checkout.java")
expect("CheckoutTest.java")
tool("find_usages", {"symbol": "CardPayment#<init>()"})
expect_ok()
expect("usages of com.acme.shop.CardPayment#<init>()")

tool("find_implementations", {"symbol": "com.acme.shop.PaymentService#pay"})
expect("2 implementations")
expect("CardPayment.java")
expect("InvoicePayment.java")
tool("find_implementations", {"symbol": "com.acme.shop.CardPayment#pay"})
expect("implements com.acme.shop.PaymentService#pay(com.acme.shop.Order)  src/main/java/com/acme/shop/PaymentService.java:4: boolean pay(Order order);")
tool("find_implementations", {"symbol": "com.acme.shop.InvoicePayment#toString"})
expect("overrides java.lang.Object#toString()  [library: java.base (JDK)]")

tool("outline", {"symbol": "com.acme.shop.Order"})
expect_ok()
expect("public method com.acme.shop.Order#addLine(java.lang.String) : Order")
expect("private final field com.acme.shop.Order#lines : List<Order.Line>")
tool("outline", {"file": "src/main/java/com/acme/shop/CardPayment.java"})
expect("class com.acme.shop.CardPayment implements PaymentService")

# library types: the JDK and the dependencies, read from the class files
tool("outline", {"symbol": "java.util.ArrayList"})
expect_ok()
expect("java.util.ArrayList  [library: java.base (JDK)")
expect("public method java.util.ArrayList#add(java.lang.Object) : boolean")
tool("outline", {"symbol": "Assertions", "limit": 400})
expect("org.junit.jupiter.api.Assertions  [library: junit-jupiter-api-5.10.2.jar")
expect("public static method org.junit.jupiter.api.Assertions#fail() : <V> V")
tool("find_symbol", {"query": "Assertions#assertThrows", "include_libraries": True})
expect("org.junit.jupiter.api.Assertions#assertThrows(java.lang.Class,org.junit.jupiter.api.function.Executable)  method  "
       "[library: junit-jupiter-api-5.10.2.jar]")
tool("find_symbol", {"query": "util.Date#getYear", "include_libraries": True})
expect("java.util.Date#getYear()  method  [library: java.base (JDK)]  (deprecated)")

tool("diagnostics", {})
expect("No compile errors in the workspace")
tool("diagnostics", {"files": ["src/main/java/com/acme/shop/Checkout.java", "src/main/java/Missing.java"], "include_warnings": True})
expect_ok()
expect("not found: src/main/java/Missing.java")

# --- 4. edits on disk show up in the next call ------------------------------------------------

checkout = os.path.join(pkg, "Checkout.java")
write(checkout, 'class ExtraUsage { Order o = new Order().addLine("added-by-smoke-test", 7); }\n', "a")
tool("find_usages", {"symbol": "com.acme.shop.Order#addLine(String,int)"})
expect("added-by-smoke-test")
before_error = read(checkout)
write(checkout, 'class Broken { void x() { new Order().addLin("typo"); } }\n', "a")
tool("diagnostics", {})
expect("1 error in 1 file")
expect("src/main/java/com/acme/shop/Checkout.java")
expect("16:39 error: cannot find symbol")
write(checkout, before_error)
tool("diagnostics", {})
expect("No compile errors in the workspace")

# --- 5. rename ---------------------------------------------------------------------------------

tool("rename", {"symbol": "com.acme.shop.Order#addLine(String,int)", "new_name": "1bad"})
expect_error()
expect("not a valid Java identifier")
tool("rename", {"symbol": "com.acme.shop.Order#addLine(String,int)", "new_name": "addItem"})
expect_ok()
expect("Done: rename com.acme.shop.Order#addLine(java.lang.String,int) to addItem. 2 files changed")
expect("+        Order order = new Order().addItem(sku, quantity);")
expect('+class ExtraUsage { Order o = new Order().addItem("added-by-smoke-test", 7); }')
if 'addLine("pear")' not in read(os.path.join(test_pkg, "CheckoutTest.java")):
    fail("the addLine(String) overload must not be renamed")
# the returned diff is a valid patch: applying it in reverse restores the old content
if shutil.which("git") and "diff --git" in last["text"]:
    patch = os.path.join(work, "rename.patch")
    with open(patch, "w", newline="") as f:
        f.write(last["text"][last["text"].index("diff --git"):])
    if subprocess.run(["git", "apply", "-R", "--check", patch], cwd=project, capture_output=True).returncode != 0:
        fail("the rename diff does not apply in reverse")

# a public class: its file is renamed too
tool("rename", {"symbol": "com.acme.shop.CardPayment", "new_name": "CreditCardPayment"})
expect_ok()
expect("rename from src/main/java/com/acme/shop/CardPayment.java")
expect("rename to src/main/java/com/acme/shop/CreditCardPayment.java")
if not os.path.isfile(os.path.join(pkg, "CreditCardPayment.java")) or os.path.exists(os.path.join(pkg, "CardPayment.java")):
    fail("CardPayment.java should have been renamed to CreditCardPayment.java")
# an interface method: the implementations follow
tool("rename", {"symbol": "com.acme.shop.PaymentService#pay", "new_name": "charge"})
expect_ok()
expect("+    boolean charge(Order order);")
expect("src/main/java/com/acme/shop/InvoicePayment.java")
tool("find_usages", {"symbol": "com.acme.shop.Order#addItem(String,int)"})
expect("3 usages")
tool("diagnostics", {})
expect("No compile errors in the workspace")

# --- 6. more refactorings -------------------------------------------------------------------

tool("safe_delete", {"symbol": "com.acme.shop.Pricing#withTax(int)"})
expect_error()
expect("still used")
tool("safe_delete", {"symbol": "com.acme.shop.Pricing#legacyRound(int)"})
expect_ok()
expect("-    static int legacyRound(int value) {")
tool("inline", {"symbol": "com.acme.shop.Pricing#TAX_PERCENT"})
expect_ok()
expect("+        return net + net * 20 / 100;")
tool("inline", {"symbol": "com.acme.shop.Pricing#withTax(int)"})
expect_ok()
tool("safe_delete", {"symbol": "com.acme.shop.Pricing"})
expect_ok()
expect("deleted file mode 100644")
if os.path.exists(os.path.join(pkg, "Pricing.java")):
    fail("Pricing.java should have been deleted")
tool("change_signature", {"symbol": "com.acme.shop.Checkout#buy(String,int)", "parameters": [
    {"from": "sku"}, {"from": "quantity", "name": "count"}, {"name": "express", "type": "boolean", "default": "false"}]})
expect_ok()
expect("+    public boolean buy(String sku, int count, boolean express) {")
expect('+        assertTrue(new Checkout(new CreditCardPayment()).buy("apple", 3, false));')
tool("move", {"symbol": "com.acme.shop.InvoicePayment", "target_package": "com.acme.billing"})
expect_ok()
expect("rename to src/main/java/com/acme/billing/InvoicePayment.java")
expect("+package com.acme.billing;")
if not os.path.isfile(os.path.join(project, "src", "main", "java", "com", "acme", "billing", "InvoicePayment.java")):
    fail("InvoicePayment.java should be in com/acme/billing")
tool("diagnostics", {})
expect("No compile errors in the workspace")

# --- 7. structural search and replace -----------------------------------------------------------

tool("apply_rule", {"rule": "$s.length() == 0 :: $s instanceof java.lang.String"})
expect_ok()
expect("2 matching lines in 1 file")
expect_not("lines.size()")
rule = "$s.length() == 0 :: $s instanceof java.lang.String => $s.isEmpty() ;;"
tool("apply_rule", {"rule": rule, "dry_run": True})
expect("Dry run, nothing written: 1 file would change")
if "isEmpty" in read(os.path.join(pkg, "Validation.java")):
    fail("a dry run must not write")
tool("apply_rule", {"rule": rule, "paths": ["src/main/java"]})
expect("Applied: 1 file changed and saved")
if read(os.path.join(pkg, "Validation.java")).count("isEmpty()") != 2:
    fail("the rewrite was not written to disk")
tool("apply_rule", {"rule": "$a.foo((( => bar ;;"})
expect_error()
expect("does not parse as Java")

# --- 8. debugging --------------------------------------------------------------------------------

card = os.path.join(pkg, "CreditCardPayment.java")
tool("debug", {"stop_at": "com.acme.shop.CreditCardPayment#charge", "expressions": ["order.total()"]})
expect_ok()
expect("Stopped: breakpoint at com.acme.shop.CreditCardPayment.charge(CreditCardPayment.java:")
expect("com.acme.shop.CheckoutTest.cardPaymentAcceptsSmallOrders (CheckoutTest.java:")
expect("order.total() = 36")
expect("The test JVM was ended after the snapshot")
replace_in(card, "return order.total() < 1000;", "return order.total() > 1000;")
tool("debug", {"exception": "AssertionFailedError", "test": "CheckoutTest"})
expect_ok()
expect("Stopped: org.opentest4j.AssertionFailedError thrown at")
expect("expected: <true> but was: <false>")
replace_in(card, "return order.total() > 1000;", "return order.total() < 1000;")
tool("debug", {"stop_at": "com.acme.shop.CreditCardPayment#charge", "test": "CheckoutTest", "when": "order.total() > 1000"})
expect_error()
expect("'order.total() > 1000' held 0 times")

finish()
