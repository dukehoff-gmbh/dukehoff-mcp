#!/usr/bin/env bash
#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

# run in the project directory; exit 0 = correct
fail() { echo "$*"; exit 1; }
./compile.sh > /dev/null 2>&1 || fail "does not compile"
if grep -rn 'legacyTotal()' --include='*.java' . | grep -v '"[^"]*legacyTotal[^"]*"' ; then fail "legacyTotal() still declared or called"; fi
grep -q 'order.total()).append(")")' core/src/main/java/com/acme/core/Receipt.java || fail "Receipt call not migrated to total()"
grep -q 'System.out.println("legacyTotal was " + order.total());' app/src/main/java/com/acme/app/Shop.java || fail "Shop call not migrated (or its message was changed)"
out=$(mktemp -d); javac -d "$out" $(find . -name '*.java' -path '*/src/*') 2> /dev/null
java -cp "$out" com.acme.core.OrderTest > /dev/null 2>&1 || { rm -rf "$out"; fail "OrderTest fails"; }
rm -rf "$out"
echo "correct"
