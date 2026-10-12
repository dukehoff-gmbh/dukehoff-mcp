#!/usr/bin/env bash
#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

# Makes the workspace for the platform debug tasks: a copy of platform/ (Kestrel Billing Platform,
# invented: 46 classes and a month of data for 120 customers, ~4,000 meter records) whose correction
# merger compares the written timestamps as text instead of as instants, committed as the only
# commit. Of 45 corrections, only one of c-1187's is written on the same day as the reading it
# replaces, in another UTC offset ("...T08:40:00Z" sorts before "...T09:10:00+01:00"), so the
# reading wins over its correction and only c-1187's invoice is wrong (5997.05 instead of 5987.15
# CHF). The code reads correctly; which record wins shows only at runtime.
#
# Usage: setup-platform-bug.sh <new directory>
set -eu
here=$(cd "$(dirname "$0")" && pwd)
dst=$1
[ ! -e "$dst" ] || { echo "$dst exists" >&2; exit 2; }
mkdir -p "$dst"
(cd "$here/platform" && tar cf - --exclude target .) | (cd "$dst" && tar xf -)
f=$dst/src/main/java/com/kestrel/billing/usage/CorrectionMerger.java
grep -q 'Comparator.comparing(MeterReading::recordedInstant);' "$f" || { echo "unexpected CorrectionMerger" >&2; exit 2; }
sed -i.orig -e 's#Comparator.comparing(MeterReading::recordedInstant);#Comparator.comparing(MeterReading::recordedAt);#' "$f"
rm "$f.orig"
cd "$dst"
printf 'target/\n' > .gitignore
git init -q
git add -A
git -c user.name=dev -c user.email=dev@kestrel.invalid -c commit.gpgsign=false commit -qm "Kestrel Billing Platform 3.2"
echo "bug injected into $f"
