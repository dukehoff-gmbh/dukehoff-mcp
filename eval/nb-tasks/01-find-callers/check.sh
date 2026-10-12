#!/usr/bin/env bash
#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

# run in the workspace (the java/ folder of apache/netbeans at c080a039d); exit 0 = correct
expected='java.lsp.server/src/org/netbeans/modules/java/lsp/server/protocol/TextDocumentServiceImpl.java:1508
java.lsp.server/src/org/netbeans/modules/java/lsp/server/protocol/TextDocumentServiceImpl.java:1593
java.lsp.server/src/org/netbeans/modules/java/lsp/server/refactoring/ChangeMethodParametersRefactoring.java:282
java.lsp.server/src/org/netbeans/modules/java/lsp/server/refactoring/ExtractSuperclassOrInterfaceRefactoring.java:245
java.lsp.server/src/org/netbeans/modules/java/lsp/server/refactoring/MoveRefactoring.java:373
java.lsp.server/src/org/netbeans/modules/java/lsp/server/refactoring/MoveRefactoring.java:383
java.lsp.server/src/org/netbeans/modules/java/lsp/server/refactoring/PullUpRefactoring.java:247
java.lsp.server/src/org/netbeans/modules/java/lsp/server/refactoring/PushDownRefactoring.java:190
refactoring.java/src/org/netbeans/modules/refactoring/java/plugins/ExtractSuperclassRefactoringPlugin.java:259
refactoring.java/src/org/netbeans/modules/refactoring/java/plugins/InlineRefactoringPlugin.java:78
refactoring.java/src/org/netbeans/modules/refactoring/java/plugins/MoveMembersRefactoringPlugin.java:114
refactoring.java/src/org/netbeans/modules/refactoring/java/spi/JavaRefactoringPlugin.java:166
refactoring.java/src/org/netbeans/modules/refactoring/java/ui/CopyClassesUI.java:156
refactoring.java/src/org/netbeans/modules/refactoring/java/ui/CopyClassesUI.java:159
refactoring.java/src/org/netbeans/modules/refactoring/java/ui/MoveClassesUI.java:174
refactoring.java/src/org/netbeans/modules/refactoring/java/ui/MoveClassesUI.java:177
refactoring.java/src/org/netbeans/modules/refactoring/java/ui/MoveClassUI.java:104
refactoring.java/src/org/netbeans/modules/refactoring/java/ui/MoveClassUI.java:93
refactoring.java/src/org/netbeans/modules/refactoring/java/ui/RenameRefactoringUI.java:120
refactoring.java/src/org/netbeans/modules/refactoring/java/ui/RenameRefactoringUI.java:127
refactoring.java/src/org/netbeans/modules/refactoring/java/ui/RenameRefactoringUI.java:145
refactoring.java/src/org/netbeans/modules/refactoring/java/ui/RenameRefactoringUI.java:160
refactoring.java/src/org/netbeans/modules/refactoring/java/ui/SafeDeleteUI.java:100
refactoring.java/src/org/netbeans/modules/refactoring/java/ui/SafeDeleteUI.java:83'
[ -f answer.txt ] || { echo "no answer.txt"; exit 1; }
got=$(sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' -e 's#^\./##' answer.txt | grep -v '^$' | sort -u)
if [ "$got" != "$expected" ]; then
    echo "wrong call sites: $(diff <(echo "$expected") <(echo "$got") | grep -c '^<') missing, $(diff <(echo "$expected") <(echo "$got") | grep -c '^>') wrong"
    exit 1
fi
changed=$(git status --porcelain -- . ':!answer.txt')
[ -z "$changed" ] || { echo "files changed: $changed"; exit 1; }
echo "correct"
