#!/usr/bin/env bash
#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

# run in the project directory; exit 0 = correct
fail() { echo "$*"; exit 1; }
./compile.sh > /dev/null 2>&1 || fail "does not compile"
for f in core/src/main/java/com/acme/core/Order.java:'note.isEmpty()' \
         core/src/main/java/com/acme/core/Catalog.java:'sku.isEmpty()' \
         core/src/test/java/com/acme/core/OrderTest.java:'empty.isEmpty()' \
         app/src/main/java/com/acme/app/Shop.java:'customer.isEmpty()'; do
    grep -qF "${f#*:}" "${f%%:*}" || fail "String check not migrated in ${f%%:*}"
done
grep -rn --include='*.java' 'note.length() == 0\|sku.length() == 0\|empty.length() == 0\|customer.length() == 0' . && fail "String length checks left"
for f in core/src/main/java/com/acme/core/Receipt.java:'sb.length() == 0' \
         app/src/main/java/com/acme/app/Main.java:'log.length() == 0' \
         app/src/main/java/com/acme/app/Main.java:'args.length == 0' \
         core/src/main/java/com/acme/core/Receipt.java:'footer.length == 0' \
         core/src/main/java/com/acme/core/Cart.java:'items.size() == 0' \
         app/src/main/java/com/acme/app/Shop.java:'skus.size() == 0'; do
    grep -qF "${f#*:}" "${f%%:*}" || fail "must stay unchanged: ${f#*:} in ${f%%:*}"
done
echo "correct"
