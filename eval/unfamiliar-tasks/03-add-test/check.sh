#!/usr/bin/env bash
#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

# run in the workspace (the unfamiliar copy made by eval/unfamiliar/make-copy.py); exit 0 = correct
# The same task as daily-tasks/03-add-test with the copy's names: the new test class must pass on
# the real code and fail on each of three coarse mutants of shortenMiddle. Needs ~/.m2 (mvn -o).
log=${TMPDIR:-/tmp}/dukehoffmcp-unfamiliar-03.log
main=src/main/java/com/lumens/toolkit/base3/TextTools.java
test=src/test/java/com/lumens/toolkit/base3/TextToolsShortenMiddleTest.java
[ -f "$test" ] || { echo "no $test"; exit 1; }
git diff --quiet -- src/main || { echo "the code under test was changed"; exit 1; }
run() {
    mvn -q -o -Dsurefire.failIfNoSpecifiedTests=false test -Dtest=TextToolsShortenMiddleTest > "$log" 2>&1
}
run || { echo "the tests fail on the real code: $(grep -m1 -E 'FAIL|ERROR' "$log")"; exit 1; }
mutant() {
    local name=$1 from=$2 to=$3
    grep -qF "$from" "$main" || { echo "mutant $name: pattern not found"; return 1; }
    python3 -c 'import sys; p, a, b = sys.argv[1:4]; s = open(p).read(); open(p, "w").write(s.replace(a, b, 1))' "$main" "$from" "$to"
    run
    local status=$?
    git checkout -q -- "$main"
    [ $status -ne 0 ] || { echo "the tests do not notice mutant $name"; return 1; }
}
# coarse on purpose: any test of the case notices them, not only one at the exact bound
mutant "no check of length >= str.length()" "|| length >= str.length() ||" "||" || exit 1
mutant "no minimum length" "length < middle.length() + 2" "length < 0" || exit 1
mutant "uneven split" "targetSting / 2 + targetSting % 2" "targetSting / 2" || exit 1
changed=$(git status --porcelain -- . ':!*.java' ':!target')
[ -z "$changed" ] || { echo "non-Java files changed: $changed"; exit 1; }
echo "correct"
