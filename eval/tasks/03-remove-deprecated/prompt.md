<!--

    Copyright 2026 Dukehoff GmbH
    SPDX-License-Identifier: Apache-2.0

-->
This is a Java project (Maven, modules core and app). The deprecated method `legacyTotal()` of `com.acme.core.Order` has to go. Replace every call of it with `total()` (it returns the same value), then delete `legacyTotal()`. Keep everything else as it is. The code must still compile: check with `./compile.sh`.
