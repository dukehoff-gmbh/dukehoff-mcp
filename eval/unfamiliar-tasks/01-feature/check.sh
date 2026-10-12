#!/usr/bin/env bash
#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

# run in the workspace (the unfamiliar copy made by eval/unfamiliar/make-copy.py); exit 0 = correct
# The same task as daily-tasks/01-feature with the copy's names. A hidden program checks the new
# method; the agent's tests must mention it and pass. Needs the dependencies in ~/.m2 (mvn -o).
log=${TMPDIR:-/tmp}/dukehoffmcp-unfamiliar-01.log
mvn -q -o test-compile > "$log" 2>&1 || { echo "does not compile: $(grep -m1 ERROR "$log")"; exit 1; }
sig=$(javap -cp target/classes com.lumens.toolkit.base3.TextTools | grep " tallyCaseless(" | sed 's/^ *//')
[ "$sig" = "public static int tallyCaseless(java.lang.CharSequence, java.lang.CharSequence);" ] \
    || { echo "wrong or missing method: $sig"; exit 1; }
hidden=$(mktemp -d)
cat > "$hidden/Hidden.java" << 'EOF'
import static com.lumens.toolkit.base3.TextTools.tallyCaseless;

public class Hidden {
    static int failures;

    static void check(String what, int expected, int actual) {
        if (expected != actual) {
            System.out.println(what + ": expected " + expected + ", got " + actual);
            failures++;
        }
    }

    public static void main(String[] args) {
        check("null str", 0, tallyCaseless(null, "a"));
        check("null sub", 0, tallyCaseless("a", null));
        check("empty str", 0, tallyCaseless("", "a"));
        check("empty sub", 0, tallyCaseless("abc", ""));
        check("single chars", 4, tallyCaseless("aAbBAA", "a"));
        check("words", 3, tallyCaseless("abAB ab", "AB"));
        check("non-overlapping", 1, tallyCaseless("aaa", "AA"));
        check("StringBuilder", 2, tallyCaseless(new StringBuilder("XyZxYz"), "xyz"));
        check("Unicode", 3, tallyCaseless("ÄäÄ", "ä"));
        check("no match", 0, tallyCaseless("abc", "x"));
        check("longer sub", 0, tallyCaseless("ab", "abc"));
        System.exit(failures == 0 ? 0 : 1);
    }
}
EOF
javac -d "$hidden" -cp target/classes "$hidden/Hidden.java" && out=$(java -cp "target/classes:$hidden" Hidden) \
    || { echo "wrong behaviour: $(echo "$out" | head -3 | tr '\n' ';')"; exit 1; }
tests=$(grep -rl "tallyCaseless" src/test/java | sed 's#.*/##; s#\.java$##' | paste -sd, -)
[ -n "$tests" ] || { echo "no tests for tallyCaseless"; exit 1; }
mvn -q -o -Dsurefire.failIfNoSpecifiedTests=false test -Dtest="$tests" > "$log" 2>&1 \
    || { echo "tests fail ($tests): $(grep -m1 -E 'FAIL|ERROR' "$log")"; exit 1; }
changed=$(git status --porcelain -- . ':!*.java' ':!target')
[ -z "$changed" ] || { echo "non-Java files changed: $changed"; exit 1; }
echo "correct"
