<!--

    Copyright 2026 Dukehoff GmbH
    SPDX-License-Identifier: Apache-2.0

-->
This is an old-style Java code base (Maven). Modernize the classes `Service01` to `Service24` in `src/main/java/com/acme/legacy`:

1. A local resource that is only closed in a `finally` block (`X r = ...; try { ... } finally { r.close(); }`) becomes try-with-resources.
2. An iterator loop that only reads (`for (Iterator<T> it = c.iterator(); it.hasNext();) { T e = it.next(); ... }`) becomes a for-each loop.
3. `m.containsKey(k) ? m.get(k) : d` on a `java.util.Map` becomes `m.getOrDefault(k, d)`.
4. `size() == 0` on a collection becomes `isEmpty()`.

Change a place only where the new form means the same and compiles; leave everything else as it is. Do not change the other classes, the tests or `pom.xml`. Check with `mvn -o -q test` (offline; all dependencies are in the local repository).
