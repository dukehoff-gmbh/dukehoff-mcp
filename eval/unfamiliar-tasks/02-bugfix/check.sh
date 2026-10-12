#!/usr/bin/env bash
#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

# run in the workspace: the unfamiliar copy with the injected bug (setup-bug.sh): the region length
# check of SequenceTools.segmentsMatch rejects regions that end at the end of the text. The same task
# as daily-tasks/02-bugfix with the copy's names. Needs the dependencies in ~/.m2.
log=${TMPDIR:-/tmp}/dukehoffmcp-unfamiliar-02.log
mvn -q -o test-compile > "$log" 2>&1 || { echo "does not compile: $(grep -m1 ERROR "$log")"; exit 1; }
hidden=$(mktemp -d)
cat > "$hidden/Hidden.java" << 'EOF'
import com.lumens.toolkit.base3.TextTools;

public class Hidden {
    static int failures;

    static void check(String what, boolean expected, boolean actual) {
        if (expected != actual) {
            System.out.println(what + ": expected " + expected);
            failures++;
        }
    }

    public static void main(String[] args) {
        check("endsWithCaseless", true, TextTools.endsWithCaseless(new StringBuilder("ABCDEF"), "def"));
        check("containsCaseless at the end", true, TextTools.containsCaseless(new StringBuilder("Hello World"), "WORLD"));
        check("containsCaseless at the start", true, TextTools.containsCaseless(new StringBuilder("Hello World"), "HELLO"));
        check("startsWithCaseless whole", true, TextTools.startsWithCaseless(new StringBuilder("abc"), "ABC"));
        check("equalsCaseless", true, TextTools.equalsCaseless(new StringBuilder("abc"), "ABC"));
        check("longer suffix", false, TextTools.endsWithCaseless(new StringBuilder("abc"), "xabc"));
        check("longer search", false, TextTools.containsCaseless(new StringBuilder("abc"), "abcd"));
        check("different text", false, TextTools.endsWithCaseless(new StringBuilder("ABCDEF"), "deg"));
        check("case-sensitive endsWith", false, TextTools.endsWith(new StringBuilder("ABCDEF"), "def"));
        System.exit(failures == 0 ? 0 : 1);
    }
}
EOF
javac -d "$hidden" -cp target/classes "$hidden/Hidden.java" && out=$(java -cp "target/classes:$hidden" Hidden) \
    || { echo "wrong behaviour: $(echo "$out" | head -3 | tr '\n' ';')"; exit 1; }
tests=SequenceToolsTest,TextToolsStartsEndsWithTest,TextToolsContainsTest,TextToolsTest
mvn -q -o -Dsurefire.failIfNoSpecifiedTests=false test -Dtest="$tests" > "$log" 2>&1 \
    || { echo "tests fail: $(grep -m1 -E 'FAIL|ERROR' "$log")"; exit 1; }
changed=$(git status --porcelain -- . ':!*.java' ':!target')
[ -z "$changed" ] || { echo "non-Java files changed: $changed"; exit 1; }
echo "correct"
