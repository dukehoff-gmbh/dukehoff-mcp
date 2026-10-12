<!--

    Copyright 2026 Dukehoff GmbH
    SPDX-License-Identifier: Apache-2.0

-->
The current directory is the `java` folder of the Apache NetBeans source tree: about 160 NetBeans module projects (Ant based, each with src/ and test/ folders). Find all classes in this folder that extend `org.netbeans.modules.refactoring.java.spi.JavaRefactoringPlugin`, directly or indirectly (through other subclasses).

Write the result to a file `answer.txt` in the current directory, one fully qualified class name per line, e.g. `org.netbeans.modules.foo.BarPlugin`. Do not change any other file.
