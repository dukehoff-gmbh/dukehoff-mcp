---
name: java-migrate
description: Use for Java migrations and modernizations that change many call sites the same way - a library or framework upgrade, replacing deprecated APIs, moving to another package (javax to jakarta), changing argument order or overloads. Writes structural rules (NetBeans Jackpot) for the apply_rule tool instead of editing file by file or with sed.
---
<!--
    Copyright 2026 Dukehoff GmbH
    SPDX-License-Identifier: Apache-2.0
-->

# Java migrations with structural rules

`apply_rule` of the `java` MCP server matches syntax trees and types, not text: nested calls, commas
inside strings, line breaks and overloads are no problem, and it writes every change in one call.
Use it whenever the same change applies to more than a handful of places.

## Workflow

1. **Inventory.** List what has to change with search rules (no `=>`), e.g.
   `org.old.Api.call($a, $b) :: $a instanceof java.lang.String && $b instanceof java.lang.Object ;;`.
   Compare the counts with a grep for the method name: a difference means a form your rules miss.
2. **One rule per overload.** A pattern matches one overload of a method: the one its typed
   variables select. `assertSomething($m, $x)` with `$m instanceof String && $x instanceof long` and
   the same with `$x instanceof java.lang.Object` are two different rules. Put specific types first.
3. **Type every variable.** Give each `$variable` an `instanceof` condition, `java.lang.Object` when
   any type is fine. Untyped variables can miss calls, e.g. statically imported methods.
4. **Dry run on a few files** (`dry_run: true`, `paths: [...]`), read the diff, then apply to all.
5. **Search again** with the old patterns: anything left is a form you did not cover.
6. **Finish by hand** what rules do not express (see Limits), remove imports that are no longer
   used, then check: `diagnostics` without arguments lists every file with compile errors; then run
   the build and the tests.

## Syntax

```
<pattern> [:: <conditions>] [=> <replacement>] ;;
```

- `$name` one expression or statement; `$name$` any number of them (e.g. all remaining arguments);
  `$_` an unnamed wildcard.
- Conditions: `$x instanceof <type>` combined with `&&`. Types fully qualified; primitive types and
  arrays work: `long`, `double`, `boolean`, `int[]`. An argument matches when it is assignable
  (an `int` argument matches `long`).
- Write types and static methods fully qualified in patterns and replacements; imports are added
  and simplified.
- Several rules in one call, each ending with `;;`.

## Examples

Reorder arguments (an API that moved its message parameter to the end):

```
com.acme.old.Check.state($msg, $cond) :: $msg instanceof java.lang.String && $cond instanceof boolean
    => com.acme.check.Check.state($cond, $msg) ;;
```

Overloads, most specific first:

```
com.acme.old.Store.put($k, $v) :: $k instanceof java.lang.String && $v instanceof int
    => com.acme.store.Store.putInt($k, $v) ;;
com.acme.old.Store.put($k, $v) :: $k instanceof java.lang.String && $v instanceof java.lang.Object
    => com.acme.store.Store.put($k, $v) ;;
```

A type that moved package (references, imports and annotations follow):

```
javax.annotation.PostConstruct => jakarta.annotation.PostConstruct ;;
```

Any number of arguments, and a receiver:

```
java.util.Arrays.asList($items$) => java.util.List.of($items$) ;;     (List.of rejects nulls: check)
$s.length() == 0 :: $s instanceof java.lang.String => $s.isEmpty() ;;
new java.lang.Integer($v) :: $v instanceof int => java.lang.Integer.valueOf($v) ;;
```

## Limits

- Annotation attributes with a changed meaning (e.g. an attribute that becomes a statement in the
  method body) and other changes of structure need hand edits; do them after the rules.
- Comments and string literals are not touched.
- A rule changes code, not build files: update dependencies in `pom.xml` or `build.gradle` yourself.
