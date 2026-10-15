<!--

    Copyright 2026 Dukehoff GmbH
    SPDX-License-Identifier: Apache-2.0

-->
This is a Maven project whose tests use JUnit 4. Migrate all tests to JUnit 5 (Jupiter): imports, annotations, assertions, expected exceptions and ignored tests. Then remove the JUnit 4 and the vintage engine dependencies from pom.xml. Do not delete, skip or weaken any test or assertion. Check with `mvn -o -q test` (offline; all dependencies are in the local repository).

Do the code changes with the `apply_rule` tool of the java MCP server (structural search and replace with NetBeans Jackpot rules), not with scripts or file-by-file edits. Edit by hand only what rules cannot express, and pom.xml.
