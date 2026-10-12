#!/usr/bin/env bash
#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

# run in the workspace (Apache Commons Lang at rel/commons-lang-3.17.0); exit 0 = correct
# A hidden program checks the new method (null/empty, case, non-overlapping, Unicode, StringBuilder);
# the agent's tests must mention it and pass. Needs the dependencies in ~/.m2 (mvn -o).
log=${TMPDIR:-/tmp}/dukehoffmcp-daily-01.log
mvn -q -o -Drat.skip=true test-compile > "$log" 2>&1 || { echo "does not compile: $(grep -m1 ERROR "$log")"; exit 1; }
sig=$(javap -cp target/classes org.apache.commons.lang3.StringUtils | grep " countMatchesIgnoreCase(" | sed 's/^ *//')
[ "$sig" = "public static int countMatchesIgnoreCase(java.lang.CharSequence, java.lang.CharSequence);" ] \
    || { echo "wrong or missing method: $sig"; exit 1; }
hidden=$(mktemp -d)
cat > "$hidden/Hidden.java" << 'EOF'
import static org.apache.commons.lang3.StringUtils.countMatchesIgnoreCase;

public class Hidden {
    static int failures;

    static void check(String what, int expected, int actual) {
        if (expected != actual) {
            System.out.println(what + ": expected " + expected + ", got " + actual);
            failures++;
        }
    }

    public static void main(String[] args) {
        check("null str", 0, countMatchesIgnoreCase(null, "a"));
        check("null sub", 0, countMatchesIgnoreCase("a", null));
        check("empty str", 0, countMatchesIgnoreCase("", "a"));
        check("empty sub", 0, countMatchesIgnoreCase("abc", ""));
        check("single chars", 4, countMatchesIgnoreCase("aAbBAA", "a"));
        check("words", 3, countMatchesIgnoreCase("abAB ab", "AB"));
        check("non-overlapping", 1, countMatchesIgnoreCase("aaa", "AA"));
        check("StringBuilder", 2, countMatchesIgnoreCase(new StringBuilder("XyZxYz"), "xyz"));
        check("Unicode", 3, countMatchesIgnoreCase("ÄäÄ", "ä"));
        check("no match", 0, countMatchesIgnoreCase("abc", "x"));
        check("longer sub", 0, countMatchesIgnoreCase("ab", "abc"));
        System.exit(failures == 0 ? 0 : 1);
    }
}
EOF
javac -d "$hidden" -cp target/classes "$hidden/Hidden.java" && out=$(java -cp "target/classes:$hidden" Hidden) \
    || { echo "wrong behaviour: $(echo "$out" | head -3 | tr '\n' ';')"; exit 1; }
tests=$(grep -rl "countMatchesIgnoreCase" src/test/java | sed 's#.*/##; s#\.java$##' | paste -sd, -)
[ -n "$tests" ] || { echo "no tests for countMatchesIgnoreCase"; exit 1; }
mvn -q -o -Drat.skip=true -Dsurefire.failIfNoSpecifiedTests=false test -Dtest="$tests" > "$log" 2>&1 \
    || { echo "tests fail ($tests): $(grep -m1 -E 'FAIL|ERROR' "$log")"; exit 1; }
changed=$(git status --porcelain -- . ':!*.java' ':!target')
[ -z "$changed" ] || { echo "non-Java files changed: $changed"; exit 1; }
echo "correct"
