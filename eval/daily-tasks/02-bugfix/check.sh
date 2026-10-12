#!/usr/bin/env bash
#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

# run in the workspace: Commons Lang 3.17.0 with an injected bug (setup-bug.sh): the region length
# check of CharSequenceUtils.regionMatches rejects regions that end at the end of the text, so the
# non-String path of the *IgnoreCase methods fails there. A hidden program checks the symptoms and
# the bounds; the existing tests of those methods must pass. Needs the dependencies in ~/.m2.
log=${TMPDIR:-/tmp}/dukehoffmcp-daily-02.log
mvn -q -o -Drat.skip=true test-compile > "$log" 2>&1 || { echo "does not compile: $(grep -m1 ERROR "$log")"; exit 1; }
hidden=$(mktemp -d)
cat > "$hidden/Hidden.java" << 'EOF'
import org.apache.commons.lang3.StringUtils;

public class Hidden {
    static int failures;

    static void check(String what, boolean expected, boolean actual) {
        if (expected != actual) {
            System.out.println(what + ": expected " + expected);
            failures++;
        }
    }

    public static void main(String[] args) {
        check("endsWithIgnoreCase", true, StringUtils.endsWithIgnoreCase(new StringBuilder("ABCDEF"), "def"));
        check("containsIgnoreCase at the end", true, StringUtils.containsIgnoreCase(new StringBuilder("Hello World"), "WORLD"));
        check("containsIgnoreCase at the start", true, StringUtils.containsIgnoreCase(new StringBuilder("Hello World"), "HELLO"));
        check("startsWithIgnoreCase whole", true, StringUtils.startsWithIgnoreCase(new StringBuilder("abc"), "ABC"));
        check("equalsIgnoreCase", true, StringUtils.equalsIgnoreCase(new StringBuilder("abc"), "ABC"));
        check("longer suffix", false, StringUtils.endsWithIgnoreCase(new StringBuilder("abc"), "xabc"));
        check("longer search", false, StringUtils.containsIgnoreCase(new StringBuilder("abc"), "abcd"));
        check("different text", false, StringUtils.endsWithIgnoreCase(new StringBuilder("ABCDEF"), "deg"));
        check("case-sensitive endsWith", false, StringUtils.endsWith(new StringBuilder("ABCDEF"), "def"));
        System.exit(failures == 0 ? 0 : 1);
    }
}
EOF
javac -d "$hidden" -cp target/classes "$hidden/Hidden.java" && out=$(java -cp "target/classes:$hidden" Hidden) \
    || { echo "wrong behaviour: $(echo "$out" | head -3 | tr '\n' ';')"; exit 1; }
tests=CharSequenceUtilsTest,StringUtilsStartsEndsWithTest,StringUtilsContainsTest,StringUtilsTest
mvn -q -o -Drat.skip=true -Dsurefire.failIfNoSpecifiedTests=false test -Dtest="$tests" > "$log" 2>&1 \
    || { echo "tests fail: $(grep -m1 -E 'FAIL|ERROR' "$log")"; exit 1; }
changed=$(git status --porcelain -- . ':!*.java' ':!target')
[ -z "$changed" ] || { echo "non-Java files changed: $changed"; exit 1; }
echo "correct"
