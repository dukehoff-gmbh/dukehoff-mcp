#!/usr/bin/env bash
#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

# run in the workspace (Apache Commons Lang at rel/commons-lang-3.17.0); exit 0 = correct
# expectation: ArrayUtils#isEmpty(Object[]) is private and ArrayUtils.java is the only changed file. The
# project no longer compiles (its callers break), so ArrayUtils.java alone is compiled against the classes of
# the unchanged sources and read with javap
file=src/main/java/org/apache/commons/lang3/ArrayUtils.java
changed=$(git status --porcelain | grep -v " $file\$")
[ -z "$changed" ] || { echo "other files changed: $changed"; exit 1; }
git diff --quiet -- "$file" && { echo "ArrayUtils.java is unchanged"; exit 1; }
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/orig" "$tmp/orig-classes" "$tmp/classes"
git archive HEAD src/main/java | tar -x -C "$tmp/orig"
find "$tmp/orig/src/main/java" -name '*.java' > "$tmp/sources"
javac -nowarn -encoding UTF-8 -proc:none -d "$tmp/orig-classes" @"$tmp/sources" > "$tmp/javac.log" 2>&1 \
    || { echo "the unchanged sources do not compile:"; tail -5 "$tmp/javac.log"; exit 1; }
javac -nowarn -encoding UTF-8 -proc:none -implicit:none -cp "$tmp/orig-classes" -d "$tmp/classes" "$file" > "$tmp/javac.log" 2>&1 \
    || { echo "ArrayUtils.java does not compile:"; tail -5 "$tmp/javac.log"; exit 1; }
decl=$(javap -p -cp "$tmp/classes" org.apache.commons.lang3.ArrayUtils | grep ' isEmpty(java.lang.Object\[\])')
case "$decl" in
    *private*) echo "correct: $decl" ;;
    *) echo "not private: $decl"; exit 1 ;;
esac
