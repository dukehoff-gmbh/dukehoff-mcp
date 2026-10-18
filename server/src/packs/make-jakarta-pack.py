#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

"""Generates the rule pack jakarta (javax.* to jakarta.*) from the API jars of both namespaces.

For every public type of a javax API jar that exists under the same name in the jakarta jar, one
rule: javax.persistence.Entity => jakarta.persistence.Entity ;; (type references, imports and
annotations follow). Types of the JDK's own javax packages (javax.sql, javax.crypto, javax.swing, ...)
are in none of these jars, so they keep their names. The jars come from the local Maven repository
(mvn dependency:get fetches them).

Usage: make-jakarta-pack.py <output .rules file>
"""

import os
import re
import subprocess
import sys
import zipfile

M2 = os.path.expanduser("~/.m2/repository")
# javax artifact, jakarta artifact (group:artifact:version)
APIS = [
    ("javax.servlet:javax.servlet-api:4.0.1", "jakarta.servlet:jakarta.servlet-api:6.0.0"),
    ("javax.persistence:javax.persistence-api:2.2", "jakarta.persistence:jakarta.persistence-api:3.1.0"),
    ("javax.validation:validation-api:2.0.1.Final", "jakarta.validation:jakarta.validation-api:3.0.2"),
    ("javax.annotation:javax.annotation-api:1.3.2", "jakarta.annotation:jakarta.annotation-api:2.1.1"),
    ("javax.inject:javax.inject:1", "jakarta.inject:jakarta.inject-api:2.0.1"),
    ("javax.ws.rs:javax.ws.rs-api:2.1.1", "jakarta.ws.rs:jakarta.ws.rs-api:3.1.0"),
    ("javax.transaction:javax.transaction-api:1.3", "jakarta.transaction:jakarta.transaction-api:2.0.1"),
    ("javax.xml.bind:jaxb-api:2.3.1", "jakarta.xml.bind:jakarta.xml.bind-api:4.0.0"),
    ("javax.activation:javax.activation-api:1.2.0", "jakarta.activation:jakarta.activation-api:2.1.0"),
    ("javax.mail:javax.mail-api:1.6.2", "jakarta.mail:jakarta.mail-api:2.1.0"),
    ("javax.json:javax.json-api:1.1.4", "jakarta.json:jakarta.json-api:2.1.0"),
    ("javax.json.bind:javax.json.bind-api:1.0", "jakarta.json.bind:jakarta.json.bind-api:3.0.0"),
    ("javax.jms:javax.jms-api:2.0.1", "jakarta.jms:jakarta.jms-api:3.1.0"),
    ("javax.ejb:javax.ejb-api:3.2.2", "jakarta.ejb:jakarta.ejb-api:4.0.1"),
    ("javax.enterprise:cdi-api:2.0", "jakarta.enterprise:jakarta.enterprise.cdi-api:4.0.1"),
    ("javax.interceptor:javax.interceptor-api:1.2", "jakarta.interceptor:jakarta.interceptor-api:2.1.0"),
    ("javax.faces:javax.faces-api:2.3", "jakarta.faces:jakarta.faces-api:4.0.1"),
    ("javax.websocket:javax.websocket-api:1.1", "jakarta.websocket:jakarta.websocket-api:2.1.0"),
    ("javax.servlet.jsp:javax.servlet.jsp-api:2.3.3", "jakarta.servlet.jsp:jakarta.servlet.jsp-api:3.1.0"),
    ("javax.el:javax.el-api:3.0.0", "jakarta.el:jakarta.el-api:5.0.1"),
]
NOTES = [
    "Left for hand edits (rules change Java code only):",
    "- Dependencies in pom.xml or build.gradle: the javax APIs to their jakarta versions (and an implementation, server or",
    "  framework version that supports Jakarta EE 9+, e.g. Spring Boot 3, Hibernate 6, Tomcat 10).",
    "- Configuration files: persistence.xml, web.xml, beans.xml (namespaces and versions), META-INF/services entries.",
    "- String literals with javax names, e.g. property keys like \"javax.persistence.jdbc.url\".",
    "- Files with wildcard imports (import javax.persistence.*;) get the jakarta types written fully qualified; shorten",
    "  them with the IDE's import cleanup if you like.",
    "- The JDK's own javax packages (javax.sql, javax.crypto, javax.naming, javax.swing, ...) and JCache (javax.cache)",
    "  stay as they are.",
]


def jar(coordinate):
    group, artifact, version = coordinate.split(":")
    path = os.path.join(M2, *group.split("."), artifact, version, f"{artifact}-{version}.jar")
    if not os.path.isfile(path):
        sys.exit(f"missing {path}: mvn dependency:get -Dartifact={coordinate}")
    return path


def public_types(path):
    """Binary names (with $ for nested types) of the public classes in a jar."""
    with zipfile.ZipFile(path) as z:
        classes = [n[:-6].replace("/", ".") for n in z.namelist()
                   if n.endswith(".class") and not n.endswith(("module-info.class", "package-info.class"))
                   and not re.search(r"\$\d", n)]
    out = subprocess.run(["javap", "-public", "-cp", path] + classes, capture_output=True, text=True).stdout
    names = set()
    for line in out.splitlines():
        m = re.match(r"^public .*?\b(?:class|interface|enum|@interface) ([\w.$]+)", line)
        if m:
            names.add(m.group(1))
    return names


rules = []
seen = set()
for javax, jakarta in APIS:
    old, new = public_types(jar(javax)), public_types(jar(jakarta))
    for name in sorted(old):
        target = "jakarta." + name[len("javax."):]
        if name.startswith("javax.") and target in new and name not in seen:
            seen.add(name)
            rules.append(f"{name.replace('$', '.')} => {target.replace('$', '.')} ;;")
    print(f"{javax}: {sum(1 for r in rules if r.startswith(javax.split(':')[0]))} types", file=sys.stderr)

with open(sys.argv[1], "w") as f:
    f.write("#\n# Copyright 2026 Dukehoff GmbH\n# SPDX-License-Identifier: Apache-2.0\n#\n")
    f.write("# javax to jakarta: Servlet, JSP, EL, Persistence, Validation, Annotation, Inject, CDI, Interceptors,\n"
            "# EJB, JAX-RS, Transaction, JAXB, Activation, Mail, JSON-P, JSON-B, JMS, Faces, WebSocket.\n")
    f.write("# Generated by server/src/packs/make-jakarta-pack.py from the API jars; do not edit by hand.\n")
    for n in NOTES:
        f.write(f"#! {n}\n")
    f.write("\n".join(rules) + "\n")
print(f"{len(rules)} rules", file=sys.stderr)
