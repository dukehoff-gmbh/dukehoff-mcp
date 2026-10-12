#!/usr/bin/env bash
#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

# run in the workspace (Apache Commons Lang at rel/commons-lang-3.17.0); exit 0 = correct
# traps: eight primitive-array overloads, unqualified calls inside ArrayUtils, StringUtils.isEmpty
# and ObjectUtils.isEmpty; expectation: the compile errors after removing the overload
expected='src/main/java/org/apache/commons/lang3/ArrayUtils.java:3095
src/main/java/org/apache/commons/lang3/ArrayUtils.java:3357
src/main/java/org/apache/commons/lang3/ArrayUtils.java:4254
src/main/java/org/apache/commons/lang3/ArrayUtils.java:6460
src/main/java/org/apache/commons/lang3/ArrayUtils.java:8611
src/main/java/org/apache/commons/lang3/StringUtils.java:1156
src/main/java/org/apache/commons/lang3/StringUtils.java:1788
src/main/java/org/apache/commons/lang3/StringUtils.java:2098
src/main/java/org/apache/commons/lang3/StringUtils.java:3247
src/main/java/org/apache/commons/lang3/StringUtils.java:3278
src/main/java/org/apache/commons/lang3/StringUtils.java:3523
src/main/java/org/apache/commons/lang3/StringUtils.java:3555
src/main/java/org/apache/commons/lang3/StringUtils.java:6697
src/main/java/org/apache/commons/lang3/StringUtils.java:8072
src/main/java/org/apache/commons/lang3/text/WordUtils.java:195
src/main/java/org/apache/commons/lang3/Validate.java:256
src/test/java/org/apache/commons/lang3/ArrayUtilsTest.java:1328
src/test/java/org/apache/commons/lang3/ArrayUtilsTest.java:1329
src/test/java/org/apache/commons/lang3/ArrayUtilsTest.java:1330'
[ -f answer.txt ] || { echo "no answer.txt"; exit 1; }
got=$(sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' -e 's#^\./##' answer.txt | grep -v '^$' | sort -u)
if [ "$got" != "$expected" ]; then
    echo "wrong call sites: $(diff <(echo "$expected") <(echo "$got") | grep -c '^<') missing, $(diff <(echo "$expected") <(echo "$got") | grep -c '^>') wrong"
    exit 1
fi
changed=$(git status --porcelain -- . ':!answer.txt')
[ -z "$changed" ] || { echo "files changed: $changed"; exit 1; }
echo "correct"
