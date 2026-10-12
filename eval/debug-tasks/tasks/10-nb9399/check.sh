#!/usr/bin/env bash
#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

# run in java/java.hints of the workspace made by ../../netbeans-9399/setup.sh; exit 0 = correct
# Runs the issue's tests, a hidden test (block-bodied arms; a switch whose rules all throw must still
# count as an exit) and the other users of the exit analysis (NPECheck, Flow, Introduce hints).
# Only main code may change.
here=$(cd "$(dirname "$0")" && pwd)
hidden=test/unit/src/org/netbeans/modules/java/hints/bugs/Hidden9399Test.java
log=${TMPDIR:-/tmp}/dukehoffmcp-debug-nb9399.log
changed_tests=$(git status --porcelain -- test)
[ -z "$changed_tests" ] || { echo "tests changed: $changed_tests"; exit 1; }
other=$(git status --porcelain -- . ':!src' ':!build' | grep -v '^??.*build/' )
[ -z "$other" ] || { echo "files outside src changed: $other"; exit 1; }
cp "$here/../../netbeans-9399/Hidden9399Test.java" "$hidden"
trap 'rm -f "$hidden"' EXIT
rm -f build/test/unit/results/TEST-*.xml
classes="bugs/UnusedAssignmentOrBranchTest bugs/Hidden9399Test bugs/NPECheckTest introduce/FlowTest introduce/IntroduceHintTest"
includes=$(for c in $classes; do printf 'org/netbeans/modules/java/hints/%s.java,' "$c"; done)
ant -q -Dtest.type=unit "-Dtest.includes=${includes%,}" test-single > "$log" 2>&1
for c in $classes; do
    name=org.netbeans.modules.java.hints.${c//\//.}
    xml=build/test/unit/results/TEST-$name.xml
    [ -f "$xml" ] || { echo "no result for $name (does it compile? $(grep -m1 -E 'error:|BUILD FAILED' "$log"))"; exit 1; }
    suite=$(grep -o '<testsuite [^>]*' "$xml")
    failures=$(echo "$suite" | grep -o 'failures="[0-9]*"' | tr -dc 0-9)
    errors=$(echo "$suite" | grep -o 'errors="[0-9]*"' | tr -dc 0-9)
    if [ "$failures" != 0 ] || [ "$errors" != 0 ]; then
        echo "$name: $failures failures, $errors errors: $(grep -o 'message="[^"]*' "$xml" | head -1 | cut -c10-200)"
        exit 1
    fi
done
echo "correct"
