<!--

    Copyright 2026 Dukehoff GmbH
    SPDX-License-Identifier: Apache-2.0

-->
The current directory is Lumens Toolkit, a Maven project (sources in src/main/java, tests in src/test/java). A user reports: `TextTools.endsWithCaseless(new StringBuilder("ABCDEF"), "def")` returns false, and so does `TextTools.containsCaseless(new StringBuilder("Hello World"), "WORLD")`; with a String instead of the StringBuilder both return true. Find the cause and fix it. The project must compile and the existing tests must pass.
