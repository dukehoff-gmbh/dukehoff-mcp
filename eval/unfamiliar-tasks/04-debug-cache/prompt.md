<!--

    Copyright 2026 Dukehoff GmbH
    SPDX-License-Identifier: Apache-2.0

-->
The current directory is Lumens Toolkit, a Maven project (sources in src/main/java, tests in src/test/java). A user reports: `FastDateFormat.getInstance("EEEE", TimeZone.getTimeZone("UTC"), Locale.GERMAN)` formats a Monday as "Monday" instead of "Montag" when the same pattern was requested with `Locale.ENGLISH` earlier in the program; in a fresh JVM the German format works. Find the cause and fix it. The project must compile and the existing tests must pass.
