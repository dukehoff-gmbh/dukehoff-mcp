---
name: refactor-java-across-files
description: Change a Java method's return type or parameters, rename it, or move a class - in all implementations, overrides and callers at once. Use instead of many edits or a rewrite script whenever such a change spans several files.
---
<!--
    Copyright 2026 Dukehoff GmbH
    SPDX-License-Identifier: Apache-2.0
-->

# Refactoring Java across files

Text edits of a widely used method mean finding every implementation, override and call by grep and
editing each file. The `java` MCP server does this with the compiler's view of the code: it tells
overloads, overrides and same-named methods apart and updates all of them at once.

Symbols are fully qualified: `com.acme.Repo#delete(String,Map)`, `com.acme.Repo`, `com.acme.Repo#<init>()`.

1. **See what is affected** (optional for small changes): `find_implementations` on the method lists
   every implementation and override; `find_usages` lists the callers, grouped by file.
2. **Change the declarations in one call:**
   - return type, parameters (add, remove, reorder, retype), visibility: `change_signature`,
     e.g. `symbol: "com.acme.Repo#delete(String,Map)"`, `return_type: "Optional<T>"`.
     Overriding and implementing methods change with it; callers get new arguments.
   - name of a method, field, class or package: `rename`.
   - class to another package: `move`; something unused: `safe_delete`.
   Each returns the diff and new compile errors, if any.
3. **Finish what needs judgment by hand:** method bodies (e.g. `return true;` after a return type
   change) and callers that use the result. `diagnostics` without arguments lists every file still in
   error; fix those, then call it again until it reports none.
4. Run the project's build and tests as usual.
