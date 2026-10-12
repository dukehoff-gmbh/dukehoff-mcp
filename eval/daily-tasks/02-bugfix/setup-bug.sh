#!/usr/bin/env bash
#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

# Makes the workspace for 02-bugfix: a copy of a Commons Lang 3.17.0 checkout with the bug injected,
# committed as the only commit, so neither git diff nor the history points at it.
#
# Usage: setup-bug.sh <commons-lang checkout at rel/commons-lang-3.17.0> <new directory>
set -eu
src=$(cd "$1" && pwd)
dst=$2
[ ! -e "$dst" ] || { echo "$dst exists" >&2; exit 2; }
mkdir -p "$dst"
(cd "$src" && git ls-files -z | xargs -0 tar cf - ) | (cd "$dst" && tar xf -)
f=$dst/src/main/java/org/apache/commons/lang3/CharSequenceUtils.java
grep -q 'if (srcLen < length || otherLen < length) {' "$f" || { echo "unexpected CharSequenceUtils" >&2; exit 2; }
sed -i.orig 's/if (srcLen < length || otherLen < length) {/if (srcLen <= length || otherLen < length) {/' "$f"
rm "$f.orig"
cd "$dst"
git init -q
git add -A
git -c user.name=eval -c user.email=eval@example.invalid -c commit.gpgsign=false commit -qm "Apache Commons Lang 3.17.0"
echo "bug injected into $f"
