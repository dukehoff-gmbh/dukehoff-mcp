#!/usr/bin/env bash
#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

# Makes the workspace for 02-bugfix: a copy of the unfamiliar copy (eval/unfamiliar/make-copy.py)
# with the bug injected, committed as the only commit, so neither git diff nor the history points at it.
#
# Usage: setup-bug.sh <unfamiliar copy> <new directory>
set -eu
src=$(cd "$1" && pwd)
dst=$2
[ ! -e "$dst" ] || { echo "$dst exists" >&2; exit 2; }
mkdir -p "$dst"
(cd "$src" && git ls-files -z | xargs -0 tar cf - ) | (cd "$dst" && tar xf -)
f=$dst/src/main/java/com/lumens/toolkit/base3/SequenceTools.java
grep -q 'if (srcLen < length || otherLen < length) {' "$f" || { echo "unexpected SequenceTools" >&2; exit 2; }
sed -i.orig 's/if (srcLen < length || otherLen < length) {/if (srcLen <= length || otherLen < length) {/' "$f"
rm "$f.orig"
cd "$dst"
git init -q
git add -A
git -c user.name=dev -c user.email=dev@lumens.invalid -c commit.gpgsign=false commit -qm "Initial import"
echo "bug injected into $f"
