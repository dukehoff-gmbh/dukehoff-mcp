#!/usr/bin/env bash
#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

# run in the project directory; exit 0 = correct
expected='app/src/main/java/com/acme/app/Shop.java:22
app/src/main/java/com/acme/app/Shop.java:32
core/src/main/java/com/acme/core/Cart.java:18
core/src/main/java/com/acme/core/Order.java:23
core/src/test/java/com/acme/core/OrderTest.java:6'
[ -f answer.txt ] || { echo "no answer.txt"; exit 1; }
got=$(sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' -e 's#^\./##' answer.txt | grep -v '^$' | sort -u)
if [ "$got" != "$expected" ]; then
    echo "wrong call sites:"; diff <(echo "$expected") <(echo "$got")
    exit 1
fi
changed=$(git status --porcelain -- . ':!answer.txt')
[ -z "$changed" ] || { echo "source files changed: $changed"; exit 1; }
echo "correct"
