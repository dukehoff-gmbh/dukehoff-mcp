<!--

    Copyright 2026 Dukehoff GmbH
    SPDX-License-Identifier: Apache-2.0

-->
The current directory is the `java` folder of the Apache NetBeans source tree: about 160 NetBeans module projects (Ant based, each with src/ and test/ folders). Rename the public method `getClasspathInfoFor` of `org.netbeans.modules.refactoring.java.api.JavaRefactoringUtils` to `classpathInfoFor` and update every call of it in this folder. Other methods named `getClasspathInfoFor` (in other classes, for example the internal `RefactoringUtils`) must keep their names.
