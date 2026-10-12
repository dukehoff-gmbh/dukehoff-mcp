#!/usr/bin/env bash
#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

# run in the workspace (the java/ folder of apache/netbeans at c080a039d); exit 0 = correct
# traps for text search: TreeUtilities calls it without a receiver, and Reformatter and
# spi.java.hints' Utilities have private methods of the same signature, called the same way
expected='java.source.base/src/org/netbeans/api/java/source/GeneratorUtilities.java:274
java.source.base/src/org/netbeans/api/java/source/TranslateIdentifier.java:106
java.source.base/src/org/netbeans/api/java/source/TreePathHandle.java:232
java.source.base/src/org/netbeans/api/java/source/TreeUtilities.java:1220
java.source.base/src/org/netbeans/api/java/source/TreeUtilities.java:241
java.source.base/src/org/netbeans/api/java/source/TreeUtilities.java:388
java.source.base/src/org/netbeans/api/java/source/WorkingCopy.java:498
java.source.base/src/org/netbeans/api/java/source/WorkingCopy.java:556'
[ -f answer.txt ] || { echo "no answer.txt"; exit 1; }
got=$(sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' -e 's#^\./##' answer.txt | grep -v '^$' | sort -u)
if [ "$got" != "$expected" ]; then
    echo "wrong call sites: $(diff <(echo "$expected") <(echo "$got") | grep -c '^<') missing, $(diff <(echo "$expected") <(echo "$got") | grep -c '^>') wrong"
    exit 1
fi
changed=$(git status --porcelain -- . ':!answer.txt')
[ -z "$changed" ] || { echo "files changed: $changed"; exit 1; }
echo "correct"
