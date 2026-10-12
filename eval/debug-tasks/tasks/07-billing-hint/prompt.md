<!--

    Copyright 2026 Dukehoff GmbH
    SPDX-License-Identifier: Apache-2.0

-->
The current directory is Kestrel Billing, a Maven project (sources in src/main/java, tests in src/test/java). Accounting reports that long-term business customers are overcharged: customer c-31 (tariff BUSINESS, 5 years) was invoiced 692.88 for this month, but by the tariff sheet the total is 688.42. The test `InvoiceServiceTest#businessLongTermCustomer` reproduces it. Find the cause and fix it in the code; the tariff configuration (src/main/resources) is correct and must not change. The project must compile and all tests must pass. Use the debugger to find the cause.
