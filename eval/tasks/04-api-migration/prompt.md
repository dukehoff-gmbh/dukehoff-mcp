<!--

    Copyright 2026 Dukehoff GmbH
    SPDX-License-Identifier: Apache-2.0

-->
This is a Java project (Maven, modules core and app). Replace every check of the form `x.length() == 0` where `x` is a `java.lang.String` with `x.isEmpty()`, in main and test sources. Do not change anything else: length checks on other types (StringBuilder, arrays) and size() checks on collections stay as they are. The code must still compile: check with `./compile.sh`.
