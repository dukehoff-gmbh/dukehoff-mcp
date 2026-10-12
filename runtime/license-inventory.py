#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

"""License inventory of the runtime: maps every file in runtime/target/dukehoffmcp to a license, using the
LICENSE file of the Apache NetBeans release the runtime is built from (its third-party tables), and
reports what it cannot map.

Usage: license-inventory.py <runtime dir> <NetBeans installation of the same release> [out dir]
Writes <out dir>/inventory.tsv (file, license, how it was mapped), and the files to ship with the
runtime: <out dir>/LICENSE (Apache-2.0 and the third-party table for the files the runtime contains),
<out dir>/NOTICE (ours from the repository root, then NetBeans') and <out dir>/licenses/ (the texts the table refers to). Prints a summary.
Run it again after changing the NetBeans version or the module set, and commit the result
(runtime/src/main/licenses), which the build copies into the runtime.
"""
import shutil

import collections
import os
import re
import sys

runtime, netbeans = sys.argv[1], sys.argv[2]
out = sys.argv[3] if len(sys.argv) > 3 else "."

# the two tables of NetBeans' LICENSE: binary files by installation path, and source files that are
# compiled into module jars, by source path (cluster/module.dir/...)
binary, sources = {}, []
section = None
for line in open(os.path.join(netbeans, "LICENSE"), encoding="utf-8"):
    if line.startswith("THIRD-PARTY COMPONENT FILE"):
        section = "binary"
        continue
    if line.startswith("Sourcefile"):
        section = "source"
        continue
    if section and (line.startswith("---") or line.startswith("(") or not line.strip() or line.startswith("*")):
        continue
    parts = line.split()
    if section == "binary" and len(parts) >= 2:
        binary[parts[0]] = parts[1]
    elif section == "source" and len(parts) >= 2:
        sources.append((parts[0], parts[1]))
by_name = collections.defaultdict(set)
for path, lic in binary.items():
    by_name[os.path.basename(path)].add(lic)

# our own files (Apache 2.0 like NetBeans, but not third-party: kept out of the third-party table)
OWN = "dukehoff-mcp-Apache-2.0"

# files not in NetBeans' tables: NetBeans' own (scripts, helpers) or third-party libraries that the
# Maven Central build names differently from the release (checked by hand against their upstream)
KNOWN = [
    (r"^sources/nb-javac-.*-sources\.jar$", "GPL-2-CP", "source of nb-javac, shipped for its license (THIRD-PARTY-SOURCES)"),
    (r"^java/maven/","Apache-2.0", "Apache Maven distribution (own LICENSE, NOTICE and lib/*.license inside)"),
    (r"^extide/ant/", "Apache-2.0", "Apache Ant distribution"),
    (r"junixsocket.*\.(jar|so|dll|dylib)$", "Apache-2.0", "junixsocket (kohlschutter.com), Apache-2.0"),
    (r"flatlaf.*\.(so|dll|dylib)$", "Apache-2.0", "FlatLaf native libraries, Apache-2.0"),
    (r"jnidispatch.*\.(so|dll|jnilib)$", "Apache-2.0", "JNA native library, dual Apache-2.0/LGPL-2.1: used under Apache-2.0"),
    (r"org-apache-commons-commons_io\.jar$", "Apache-2.0", "Apache Commons IO"),
    (r"org-apache-xml-resolver\.jar$", "Apache-2.0", "Apache XML Commons Resolver"),
    (r"net-java-html-boot\.jar$", "Apache-2.0", "HTML/Java API (Apache NetBeans html4j)"),
    (r"/modules/ext/dukehoff-mcp/lombok\.jar$", "MIT-lombok", "Project Lombok, bundled by dukehoff-mcp for analysis (MIT; text from its jar)"),
    (r"org-jspecify-jspecify\.jar$", "Apache-2.0", "JSpecify annotations"),
    (r"com-google-errorprone-annotations\.jar$", "Apache-2.0", "Error Prone annotations"),
    (r"^(platform|extide)/VERSION\.txt$|org-openidex-util\.jar$|/updater\.jar$|^java/maven-nblib/|"
     r"^java/patterns/|^java/scripts/|^java/org\.eclipse\.jdt\.core\.prefs$|^java/modules/ext/"
     r"(fakeJdkClasses\.zip|nb-custom-jshell-probe\.jar)$|^extide/modules/gradle/", "Apache-2.0",
     "Apache NetBeans (not a third-party file)"),
]

rows = []
for dirpath, _, files in os.walk(runtime):
    for f in files:
        rel = os.path.relpath(os.path.join(dirpath, f), runtime)
        if rel in binary:
            rows.append((rel, binary[rel], "path in NetBeans' LICENSE"))
        elif len(by_name.get(f, ())) == 1:
            rows.append((rel, next(iter(by_name[f])), "file name in NetBeans' LICENSE"))
        elif any(re.search(p, rel) for p, _, _ in KNOWN):
            p, lic, why = next(k for k in KNOWN if re.search(k[0], rel))
            rows.append((rel, lic, why))
        elif rel in ("LICENSE", "NOTICE", "THIRD-PARTY-SOURCES") or rel.startswith("licenses/"):
            rows.append((rel, "(license text)", "license and notice files of this distribution"))
        elif rel == "VERSION":
            rows.append((rel, OWN, "our version file"))
        elif f == "com-dukehoff-mcp.jar" or "com-dukehoff-mcp" in f:
            rows.append((rel, OWN, "our module"))
        elif rel.startswith(("bin/dukehoff-mcp",)):
            rows.append((rel, OWN, "our launcher"))
        elif rel.startswith("etc/"):
            rows.append((rel, OWN, "our configuration"))
        elif re.search(r"(^|/)(org-netbeans-|org-openide-|core|boot\.jar|nbexec|update_tracking|config/|\.lastModified|"
                       r"dukehoffmcp(64)?\.exe$|bin/dukehoffmcp$|org-apache-tools-ant-module)", rel) or rel.endswith((".xml", ".properties")):
            rows.append((rel, "Apache-2.0", "Apache NetBeans (not a third-party file)"))
        else:
            rows.append((rel, "UNKNOWN", "not in NetBeans' LICENSE"))

# source files under a third-party license, compiled into a module that is in the runtime
modules = set()
for cluster in os.listdir(runtime):
    cfg = os.path.join(runtime, cluster, "config", "Modules")
    if os.path.isdir(cfg):
        modules.update(m[:-4] for m in os.listdir(cfg))
inside = []
for path, lic in sources:
    seg = path.split("/")
    if len(seg) > 2:
        dash = seg[1].replace(".", "-")
        if any(m.endswith("-" + dash) or m == dash for m in modules):
            inside.append((path, lic))

os.makedirs(out, exist_ok=True)
with open(os.path.join(out, "inventory.tsv"), "w") as w:
    for r in sorted(rows):
        w.write("\t".join(r) + "\n")
    for path, lic in sorted(inside):
        w.write(f"(source in a module jar) {path}\t{lic}\tsource table of NetBeans' LICENSE\n")

# the files to ship: NetBeans' Apache-2.0 text and intro, our table, the license texts, NetBeans' NOTICE
nb_license = open(os.path.join(netbeans, "LICENSE"), encoding="utf-8").read()
head = nb_license[:nb_license.index("THIRD-PARTY COMPONENT FILE")]
# third-party files: everything except our own files, NetBeans' own and the scripts of the bundled
# Maven and Ant distributions (whose jars are in NetBeans' table)
third = [(f, lic) for f, lic, how in rows if lic not in (OWN, "(license text)")
         and not how.startswith(("Apache NetBeans", "Apache Maven distribution", "Apache Ant distribution"))]
with open(os.path.join(out, "LICENSE"), "w", encoding="utf-8") as w:
    w.write(head)
    w.write("THIRD-PARTY COMPONENT FILE                                               LICENSE\n")
    w.write("(path in the runtime)                    (text is in file in licenses directory)\n")
    w.write("-" * 80 + "\n")
    for f, lic in sorted(third):
        w.write(f"{f:<70}{lic}\n")
    w.write("\n" + "*" * 80 + "\nSource files compiled into the modules of this runtime that are not covered "
            "by the Apache license.\n" + "*" * 80 + "\n\n")
    w.write("Sourcefile (path in the Apache NetBeans source)                              LICENSE\n")
    w.write("-" * 80 + "\n")
    for path, lic in sorted(inside):
        w.write(f"{path:<100}{lic}\n")
texts = os.path.join(out, "licenses")
shutil.rmtree(texts, ignore_errors=True)
os.makedirs(texts)
needed = {lic for _, lic in third} | {lic for _, lic in inside} | {"Apache-2.0"}
missing = []
for lic in sorted(needed):
    # NetBeans' license texts, then ours for what dukehoff-mcp bundles itself (src/main/license-texts)
    src = os.path.join(netbeans, "licenses", lic)
    if not os.path.exists(src):
        src = os.path.join(os.path.dirname(os.path.abspath(__file__)), "src", "main", "license-texts", lic)
    if os.path.exists(src):
        shutil.copy(src, texts)
    else:
        missing.append(lic)
# NOTICE: ours (the repository's) first, then NetBeans'
own_notice = os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, "NOTICE")
with open(os.path.join(out, "NOTICE"), "w", encoding="utf-8") as w:
    w.write(open(own_notice, encoding="utf-8").read().rstrip("\n") + "\n\n" + "-" * 80 + "\n\n")
    w.write(open(os.path.join(netbeans, "NOTICE"), encoding="utf-8").read())

counts = collections.Counter(lic for _, lic, _ in rows)
print(f"{len(rows)} files")
for lic, n in counts.most_common():
    print(f"  {n:5d}  {lic}")
print(f"{len(inside)} third-party source files compiled into included modules:")
for lic, n in collections.Counter(l for _, l in inside).most_common():
    print(f"  {n:5d}  {lic}")
print(f"license texts: {len(needed) - len(missing)} copied" + (f", MISSING: {missing}" if missing else ""))
unknown = [r for r in rows if r[1] == "UNKNOWN"]
if unknown:
    print("UNKNOWN:")
    for r in unknown:
        print("  " + r[0])
