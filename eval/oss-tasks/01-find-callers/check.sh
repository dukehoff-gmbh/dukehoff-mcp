#!/usr/bin/env bash
#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

# run in the workspace (Apache Commons Lang at rel/commons-lang-3.17.0); exit 0 = correct
# a distinctive name: a control task where text search is enough
expected='src/main/java/org/apache/commons/lang3/time/StopWatch.java:276
src/main/java/org/apache/commons/lang3/time/StopWatch.java:286
src/test/java/org/apache/commons/lang3/time/DurationFormatUtilsTest.java:252
src/test/java/org/apache/commons/lang3/time/DurationFormatUtilsTest.java:255
src/test/java/org/apache/commons/lang3/time/DurationFormatUtilsTest.java:258
src/test/java/org/apache/commons/lang3/time/DurationFormatUtilsTest.java:261
src/test/java/org/apache/commons/lang3/time/DurationFormatUtilsTest.java:264
src/test/java/org/apache/commons/lang3/time/DurationFormatUtilsTest.java:267
src/test/java/org/apache/commons/lang3/time/DurationFormatUtilsTest.java:270
src/test/java/org/apache/commons/lang3/time/DurationFormatUtilsTest.java:273
src/test/java/org/apache/commons/lang3/time/DurationFormatUtilsTest.java:276
src/test/java/org/apache/commons/lang3/time/DurationFormatUtilsTest.java:404'
[ -f answer.txt ] || { echo "no answer.txt"; exit 1; }
got=$(sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' -e 's#^\./##' answer.txt | grep -v '^$' | sort -u)
if [ "$got" != "$expected" ]; then
    echo "wrong call sites: $(diff <(echo "$expected") <(echo "$got") | grep -c '^<') missing, $(diff <(echo "$expected") <(echo "$got") | grep -c '^>') wrong"
    exit 1
fi
changed=$(git status --porcelain -- . ':!answer.txt')
[ -z "$changed" ] || { echo "files changed: $changed"; exit 1; }
echo "correct"
