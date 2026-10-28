<!--

    Copyright 2026 Dukehoff GmbH
    SPDX-License-Identifier: Apache-2.0

-->
# Licenses of the runtime

Inventory of everything in `runtime/target/dukehoffmcp` (NetBeans 31 clusters from Maven Central minus
the exclusions in `pom.xml`, plus our module), made with `license-inventory.py` against the LICENSE
of the official Apache NetBeans 31 release. State: 2026-10-02, runtime of commit `13b6a0b`
(1,427 files, 35 third-party source files inside module jars). This is an engineering inventory, not legal advice; have it reviewed before the
first release.

## What is shipped

The build copies `src/main/licenses` into the runtime root:

- `LICENSE`: the Apache License 2.0 (NetBeans' own code) and a table of every third-party file in
  the runtime with its license, plus the third-party source files compiled into included modules.
- `NOTICE`: dukehoff-mcp's NOTICE (the repository's), then NetBeans' (it covers more components than we
  ship; over-attribution is harmless).
- `licenses/`: the 36 license texts the table refers to.
- `THIRD-PARTY-SOURCES`: where the source of every copyleft component is (Maven Central
  coordinates, upstream releases), and `sources/`: nb-javac's source jar, copied from Maven Central
  by the build (GPL-2.0 requires the source with the binary).
- `inventory.tsv` (not shipped): every file, its license and how it was mapped.

`java/maven/` is a complete Apache Maven distribution with its own LICENSE, NOTICE and
`lib/*.license` files, shipped as they are.

Regenerate after changing the NetBeans version or the module set:
`python3 runtime/license-inventory.py runtime/target/dukehoffmcp <NetBeans installation of that release> runtime/src/main/licenses`.
It must report no `UNKNOWN` file; a new one needs a mapping rule (`KNOWN`) checked against its upstream.

## Licenses and what they require

| License | Files | Obligation | Status |
|---|---|---|---|
| Apache-2.0 (and variants: Ant, Lucene, FreeMarker, ko4j) | NetBeans' code (~1,300 files), Lucene, Ant, Maven, Gradle tooling, FlatLaf, JNA (dual Apache/LGPL, used under Apache), junixsocket, Guava, Gson, ... | Ship the license and NOTICE; mark files we changed (none); no use of the Apache/NetBeans trademarks implying endorsement | Done; trademark: see below |
| GPL-2.0 with Classpath Exception | `java/modules/ext/nb-javac-*.jar` (2) | The exception allows linking from our module under any license. Distributing the binary requires its license text **and the corresponding source** (or a written offer valid for 3 years) | Done: the source jar ships in `sources/` (no written offer needed); the build fails unless the bundled jars are byte-identical to `com.dukescript.nbjavac:nb-javac:${nbjavac.version}` on Maven Central |
| EPL-1.0 / EPL-2.0 | JUnit 4 and 5 jars and their javadoc/source jars, Eclipse Equinox/core bundles, tm4e, Sisu, jakarta.inject (26 files, plus 1 icon) | Ship the license; state where the source code can be obtained | Done: `THIRD-PARTY-SOURCES` |
| CDDL-1.0 / 1.1 | JAXB impl/xjc, javax.annotation-api, glassfish-corba-omgapi (5 files), 14 browser icons in web.browser.api | Ship the license; source of the CDDL files must be available, and the binary distribution must say how to obtain it | Done: `THIRD-PARTY-SOURCES` |
| MIT, BSD, EDL, W3C, OSGi, WSDL-2004, CC0, Public Domain | Bouncy Castle, SLF4J, ASM, ANTLR, flexmark, JSch, JZlib, jsoup, JSVG, joni/jcodings, Hamcrest, JDOM, XML schemas, Tango icons, ... | Ship the license text and copyright notices | Done |
| MIT (Project Lombok) | `extra/modules/ext/lombok-nb-javac/lombok.jar`, bundled by our plugin Lombok for nb-javac (`com.dukehoff.netbeans:lombok-nb-javac`, its own repository): used on NetBeans' processor path when a project's Lombok is too old for nb-javac | Ship the license text (`licenses/MIT-lombok`, from Lombok's jar, covers its bundled parts; the plugin's NBM carries it as `LICENSE-lombok` next to the jar) | Done |
| Gradle icon (trademark use) | 8 Gradle icons in the Gradle modules | Unmodified, no deceptive use | Unproblematic (not shown headless) |
| Apache-2.0 (dukehoff-mcp itself, `dukehoff-mcp-Apache-2.0` in the inventory) | our module, `bin/dukehoff-mcp*`, `etc/dukehoffmcp.*`, `VERSION` | Ship the license and our NOTICE (the repository's `NOTICE`, prepended to NetBeans' by `license-inventory.py`) | Done (since 2026-10-12) |

## Open before the first release

1. ~~nb-javac source~~ and 2. ~~EPL/CDDL source pointers~~: done 2026-10-12 (`sources/`,
   `THIRD-PARTY-SOURCES`; nb-javac's version is `nbjavac.version` in `runtime/pom.xml`). When the
   NetBeans release changes, update the versions in `THIRD-PARTY-SOURCES` along with the inventory.
3. **Trademarks.** "NetBeans" and "Apache" are ASF trademarks: product name and marketing must not
   suggest the ASF made or endorses the product ("based on Apache NetBeans" is the usual form).
   The provisional name `dukehoff-mcp` should be checked against the ASF trademark policy.
4. **Bundled JRE** (optional, later): OpenJDK builds are GPL-2.0 with Classpath Exception too, with
   the same source obligation; pick a vendor that publishes matching sources (e.g. Eclipse Temurin).
