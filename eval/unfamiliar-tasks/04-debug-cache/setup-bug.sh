#!/usr/bin/env bash
#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

# Makes the workspace for 04-debug-cache: a copy of the unfamiliar copy (eval/unfamiliar/make-copy.py)
# whose format cache key drops its last part (the locale) in a "defensive copy", committed as the
# only commit. The key looks right where it is built; the cause shows in its value at runtime.
#
# Usage: setup-bug.sh <unfamiliar copy> <new directory>
set -eu
src=$(cd "$1" && pwd)
dst=$2
[ ! -e "$dst" ] || { echo "$dst exists" >&2; exit 2; }
mkdir -p "$dst"
(cd "$src" && git ls-files -z | xargs -0 tar cf - ) | (cd "$dst" && tar xf -)
f=$dst/src/main/java/com/lumens/toolkit/base3/time/AbstractFormatCache.java
grep -q '            this.keys = keys;' "$f" && grep -q '            this.hashCode = computeHashCode(keys);' "$f" \
    || { echo "unexpected AbstractFormatCache" >&2; exit 2; }
sed -i.orig -e 's/            this.keys = keys;/            this.keys = Arrays.copyOf(keys, keys.length - 1);/' \
    -e 's/            this.hashCode = computeHashCode(keys);/            this.hashCode = computeHashCode(this.keys);/' "$f"
rm "$f.orig"
cd "$dst"
git init -q
git add -A
git -c user.name=dev -c user.email=dev@lumens.invalid -c commit.gpgsign=false commit -qm "Initial import"
echo "bug injected into $f"
