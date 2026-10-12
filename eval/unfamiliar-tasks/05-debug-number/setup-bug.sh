#!/usr/bin/env bash
#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

# Makes the workspace for 05-debug-number: a copy of the unfamiliar copy (eval/unfamiliar/make-copy.py)
# whose int-size check for hex numbers in NumberTools.createNumber uses >= '7' instead of > '7',
# committed as the only commit. One character in a long method; the values at runtime show it.
#
# Usage: setup-bug.sh <unfamiliar copy> <new directory>
set -eu
src=$(cd "$1" && pwd)
dst=$2
[ ! -e "$dst" ] || { echo "$dst exists" >&2; exit 2; }
mkdir -p "$dst"
(cd "$src" && git ls-files -z | xargs -0 tar cf - ) | (cd "$dst" && tar xf -)
f=$dst/src/main/java/com/lumens/toolkit/base3/math/NumberTools.java
grep -q "if (hexDigits > 8 || hexDigits == 8 && firstSigDigit > '7') { // too many for an int" "$f" \
    || { echo "unexpected NumberTools" >&2; exit 2; }
# only the int check ("too many for Long" stays)
sed -i.orig "s#firstSigDigit > '7') { // too many for an int#firstSigDigit >= '7') { // too many for an int#" "$f"
rm "$f.orig"
grep -q "firstSigDigit >= '7') { // too many for an int" "$f" || { echo "injection failed" >&2; exit 2; }
cd "$dst"
git init -q
git add -A
git -c user.name=dev -c user.email=dev@lumens.invalid -c commit.gpgsign=false commit -qm "Initial import"
echo "bug injected into $f"
