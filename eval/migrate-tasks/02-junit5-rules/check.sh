#!/usr/bin/env bash
#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

# run in the project directory; exit 0 = correct
fail() { echo "$*"; exit 1; }
counts=expected-counts.json
tests=$(python3 -c "import json; print(json.load(open('$counts'))['tests'])")
ignored=$(python3 -c "import json; print(json.load(open('$counts'))['ignored'])")

grep -q '<artifactId>junit</artifactId>' pom.xml && fail "JUnit 4 still in pom.xml"
grep -q 'junit-vintage-engine' pom.xml && fail "the vintage engine still in pom.xml"
grep -rqE 'import (static )?org\.junit\.(Test|Assert|Before|BeforeClass|After|AfterClass|Ignore)\b' src/test \
    && fail "JUnit 4 imports left"
grep -rq '@Test(expected' src/test && fail "@Test(expected = ...) left"
# a message left first in assertNull/assertNotNull compiles in JUnit 5 and checks the message instead
grep -rqE 'assert(Not)?Null\(\s*"[^"]*"\s*,' src/test && fail "assertNull/assertNotNull with the message first"
# no assertion may disappear: as many assert calls as before (assertThrows replaces expected = ...)
before=$(git grep -h -o -E 'assert[A-Z][A-Za-z]*\(' HEAD -- src/test | wc -l)
after=$(grep -rhoE 'assert[A-Z][A-Za-z]*\(' src/test | grep -v 'assertThrows(' | wc -l)
[ "$after" -ge "$before" ] || fail "assertions removed: $before before, $after now"

rm -rf target/surefire-reports
mvn -o -q test > /tmp/migrate-check-$$.log 2>&1 || { tail -20 /tmp/migrate-check-$$.log; fail "mvn -o test fails"; }
read -r run skipped < <(cat target/surefire-reports/*.txt | grep 'Tests run:' \
    | awk -F'[:,]' '{t+=$2; s+=$8} END {print t, s}')
[ "$run" = "$tests" ] || fail "$run tests ran, expected $tests"
[ "$skipped" = "$ignored" ] || fail "$skipped tests skipped, expected $ignored (the ignored ones)"
echo "correct"
