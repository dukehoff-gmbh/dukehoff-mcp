<!--

    Copyright 2026 Dukehoff GmbH
    SPDX-License-Identifier: Apache-2.0

-->
The current directory is Lumens Toolkit, a Maven project (sources in src/main/java, tests in src/test/java). Add a method `public static int tallyCaseless(CharSequence str, CharSequence sub)` to `com.lumens.toolkit.base3.TextTools`. It works like `tally(CharSequence, CharSequence)` (non-overlapping occurrences) but ignores case, and returns 0 if either argument is null or empty. Follow the style of the class, including a Javadoc with examples, and add tests for the new method next to the existing tests of `tally`. The project must compile and the tests must pass.
