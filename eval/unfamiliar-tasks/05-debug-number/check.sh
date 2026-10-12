#!/usr/bin/env bash
#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

# run in the workspace made by setup-bug.sh; exit 0 = correct
# A hidden program checks the int/long/BigInteger boundaries of hex numbers, both signs and both
# prefixes; the existing tests of NumberTools must pass.
log=${TMPDIR:-/tmp}/dukehoffmcp-unfamiliar-05.log
mvn -q -o test-compile > "$log" 2>&1 || { echo "does not compile: $(grep -m1 ERROR "$log")"; exit 1; }
hidden=$(mktemp -d)
cat > "$hidden/Hidden.java" << 'EOF'
import com.lumens.toolkit.base3.math.NumberTools;
import java.math.BigInteger;

public class Hidden {
    static int failures;

    static void check(String in, Object expected) {
        Object actual = NumberTools.createNumber(in);
        if (!expected.equals(actual)) {
            System.out.println(in + ": expected " + expected.getClass().getSimpleName() + " " + expected + ", got "
                    + (actual == null ? "null" : actual.getClass().getSimpleName() + " " + actual));
            failures++;
        }
    }

    public static void main(String[] args) {
        check("0x7FFFFFFF", Integer.MAX_VALUE);
        check("0x70000000", 0x70000000);
        check("#7FFFFFFF", Integer.MAX_VALUE);
        check("-0x7FFFFFFF", -Integer.MAX_VALUE);
        check("0x80000000", 0x80000000L);
        check("0x7FFFFFFFFFFFFFFF", Long.MAX_VALUE);
        check("0x8000000000000000", new BigInteger("8000000000000000", 16));
        check("0x1F", 31);
        System.exit(failures == 0 ? 0 : 1);
    }
}
EOF
javac -d "$hidden" -cp target/classes "$hidden/Hidden.java" && out=$(java -cp "target/classes:$hidden" Hidden) \
    || { echo "wrong behaviour: $(echo "$out" | head -3 | tr '\n' ';')"; exit 1; }
mvn -q -o -Dsurefire.failIfNoSpecifiedTests=false test -Dtest=NumberToolsTest > "$log" 2>&1 \
    || { echo "tests fail: $(grep -m1 -E 'FAIL|ERROR' "$log")"; exit 1; }
changed=$(git status --porcelain -- . ':!*.java' ':!target')
[ -z "$changed" ] || { echo "non-Java files changed: $changed"; exit 1; }
echo "correct"
