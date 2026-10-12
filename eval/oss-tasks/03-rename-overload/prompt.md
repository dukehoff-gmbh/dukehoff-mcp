<!--

    Copyright 2026 Dukehoff GmbH
    SPDX-License-Identifier: Apache-2.0

-->
The current directory is Apache Commons Lang 3.17.0, a Maven project (sources in src/main/java, tests in src/test/java). Rename the method `getShortClassName(Class)` of `org.apache.commons.lang3.ClassUtils` to `shortClassName` and update every call of it in this project. The other overloads of `ClassUtils.getShortClassName` and methods of the same name in other classes (for example `ToStringStyle.getShortClassName`) must keep their names. The project must still compile.
