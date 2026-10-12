#!/usr/bin/env bash
#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

# run in the workspace (the java/ folder of apache/netbeans at c080a039d); exit 0 = correct
fail() { echo "$*"; exit 1; }
# occurrences, not lines: two lines in RenameRefactoringUI call both the API and the internal method
count() { grep -rho "$1" --include='*.java' . | wc -l | tr -d ' '; }
grep -q 'public static ClasspathInfo classpathInfoFor(' refactoring.java/src/org/netbeans/modules/refactoring/java/api/JavaRefactoringUtils.java \
    || fail "declaration not renamed"
[ "$(count 'JavaRefactoringUtils.getClasspathInfoFor(')" = 0 ] || fail "$(count 'JavaRefactoringUtils.getClasspathInfoFor(') calls of the old name left"
[ "$(count 'JavaRefactoringUtils.classpathInfoFor(')" = 24 ] || fail "expected 24 renamed calls, found $(count 'JavaRefactoringUtils.classpathInfoFor(')"
# the 62 original occurrences minus 24 calls and the declaration: the internal overloads and their calls
[ "$(count 'getClasspathInfoFor(')" = 37 ] || fail "other getClasspathInfoFor methods changed: $(count 'getClasspathInfoFor(') occurrences instead of 37"
echo "correct"
