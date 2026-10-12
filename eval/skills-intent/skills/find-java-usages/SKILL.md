---
name: find-java-usages
description: Find who calls, uses, implements or extends a Java class or method - an exact list from the compiler's index instead of grep, which misses overrides and confuses same-named methods. Use before changing code others depend on.
---
<!--
    Copyright 2026 Dukehoff GmbH
    SPDX-License-Identifier: Apache-2.0
-->

# Who depends on this Java code?

Grep finds text: it misses calls through interfaces and subclasses, and returns every same-named
method (`get`, `delete`, `merge`) in the code base. The `java` MCP server answers from the
compiler's index.

Symbols are fully qualified: `com.acme.Repo#delete(String,Map)`, `com.acme.Repo`.

- **Callers and references:** `find_usages` on a method, field or type, grouped by file with the line.
- **Implementations, overrides, subclasses:** `find_implementations` on a type or method; for a
  method also what it overrides upwards.
- **Members of a type:** `outline` (signatures and lines, without reading the whole file).
- **A type when you only know part of its name:** `find_symbol`.

If the change itself spans many files, continue with the `refactor-java-across-files` skill.
