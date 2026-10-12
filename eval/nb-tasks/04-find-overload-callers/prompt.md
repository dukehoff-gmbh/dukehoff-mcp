<!--

    Copyright 2026 Dukehoff GmbH
    SPDX-License-Identifier: Apache-2.0

-->
The current directory is the `java` folder of the Apache NetBeans source tree: about 160 NetBeans module projects (Ant based, each with src/ and test/ folders). Find every place in this folder that calls the two-parameter method `isSynthetic(CompilationUnitTree, Tree)` of the class `org.netbeans.api.java.source.TreeUtilities`. Calls of its other overloads and of methods of the same name in other classes do not count.

Write the result to a file `answer.txt` in the current directory, one call site per line as `path:line`, with the path relative to the current directory, e.g. `java.source.base/src/org/netbeans/Foo.java:42`. Do not change any other file.
