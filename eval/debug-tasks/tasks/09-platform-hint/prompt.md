<!--

    Copyright 2026 Dukehoff GmbH
    SPDX-License-Identifier: Apache-2.0

-->
The current directory is the Kestrel Billing Platform, a Maven project (sources in src/main/java, tests in src/test/java, the March 2026 data set in data/2026-03). Accounting reports that the March invoice of customer c-1187 is too high: 5997.05 CHF, while their reference total is 5987.15 CHF. Every other invoice of the run matches the reference; `BillingRunTest` compares the whole run with it. Find the cause and fix it in the code; the data set, the tariff configuration and the reference are correct and must not change. The project must compile and all tests must pass. Use the debugger to find the cause.
