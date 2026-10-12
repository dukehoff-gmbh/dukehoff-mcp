<!--

    Copyright 2026 Dukehoff GmbH
    SPDX-License-Identifier: Apache-2.0

-->
The current directory is Lumens Toolkit, a Maven project (sources in src/main/java, tests in src/test/java). A user reports: `NumberTools.createNumber("0x7FFFFFFF")` returns a Long, although the value is `Integer.MAX_VALUE` and fits into an int; `createNumber("2147483647")` returns an Integer as expected. Find the cause and fix it. The project must compile and the existing tests must pass.
