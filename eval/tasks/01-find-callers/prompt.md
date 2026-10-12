<!--

    Copyright 2026 Dukehoff GmbH
    SPDX-License-Identifier: Apache-2.0

-->
This is a Java project (Maven, modules core and app). Find every place in the code (main and test sources) that calls the method `addLine(String sku, int quantity)` of class `com.acme.core.Order`. Only this overload counts, and only this class.

Write the result to a file `answer.txt` in the project root, one call site per line as `path:line`, with the path relative to the project root, e.g. `core/src/main/java/com/acme/core/Foo.java:42`. Do not change any other file.
