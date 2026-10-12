#!/usr/bin/env bash
#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

# Makes the workspace for the debug tasks: a copy of billing/ (Kestrel Billing, an invented project no
# model knows) whose rule priorities compare as strings, committed as the only commit. Only the
# business tariff has two-digit priorities ("12" sorts before "9"), so only long-term business
# customers are billed wrong: the loyalty credit comes before the volume discount. Every class
# looks right in itself; the order shows at runtime.
#
# Usage: setup-bug.sh <new directory>
set -eu
here=$(cd "$(dirname "$0")" && pwd)
dst=$1
[ ! -e "$dst" ] || { echo "$dst exists" >&2; exit 2; }
mkdir -p "$dst"
(cd "$here/billing" && tar cf - --exclude target .) | (cd "$dst" && tar xf -)
f=$dst/src/main/java/com/kestrel/billing/config/RuleSpec.java
grep -q 'return Integer.compare(Integer.parseInt(priority), Integer.parseInt(other.priority));' "$f" \
    || { echo "unexpected RuleSpec" >&2; exit 2; }
sed -i.orig -e 's#return Integer.compare(Integer.parseInt(priority), Integer.parseInt(other.priority));#return priority.compareTo(other.priority);#' "$f"
rm "$f.orig"
cd "$dst"
printf 'target/\n' > .gitignore
git init -q
git add -A
git -c user.name=dev -c user.email=dev@kestrel.invalid -c commit.gpgsign=false commit -qm "Kestrel Billing 1.4"
echo "bug injected into $f"
