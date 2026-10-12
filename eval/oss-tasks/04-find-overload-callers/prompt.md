<!--

    Copyright 2026 Dukehoff GmbH
    SPDX-License-Identifier: Apache-2.0

-->
The current directory is Apache Commons Lang 3.17.0, a Maven project (sources in src/main/java, tests in src/test/java). Find every place in this project that calls `org.apache.commons.lang3.ArrayUtils#isEmpty(Object[])`, the overload for object arrays. Calls of the overloads for primitive arrays and of methods named `isEmpty` in other classes do not count.

Write the result to a file `answer.txt` in the current directory, one call site per line as `path:line`, with the path relative to the current directory, e.g. `src/main/java/org/apache/commons/lang3/Foo.java:42`. Do not change any other file.
