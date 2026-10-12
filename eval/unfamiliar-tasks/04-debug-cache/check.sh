#!/usr/bin/env bash
#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

# run in the workspace made by setup-bug.sh; exit 0 = correct
# A hidden program checks the reported symptom and its date-style twin, and that the cache still
# caches (the same arguments give the same instance), so removing the cache is no fix.
log=${TMPDIR:-/tmp}/dukehoffmcp-unfamiliar-04.log
mvn -q -o test-compile > "$log" 2>&1 || { echo "does not compile: $(grep -m1 ERROR "$log")"; exit 1; }
hidden=$(mktemp -d)
cat > "$hidden/Hidden.java" << 'EOF'
import com.lumens.toolkit.base3.time.FastDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class Hidden {
    static int failures;

    static void check(String what, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            System.out.println(what + ": expected " + expected + ", got " + actual);
            failures++;
        }
    }

    public static void main(String[] args) {
        TimeZone utc = TimeZone.getTimeZone("UTC");
        Date monday = new Date(1759708800000L); // 2025-10-06, a Monday
        check("English first", "Monday", FastDateFormat.getInstance("EEEE", utc, Locale.ENGLISH).format(monday));
        check("then German", "Montag", FastDateFormat.getInstance("EEEE", utc, Locale.GERMAN).format(monday));
        check("then French", "lundi", FastDateFormat.getInstance("EEEE", utc, Locale.FRENCH).format(monday));
        check("date style, English", true,
                FastDateFormat.getDateInstance(FastDateFormat.FULL, utc, Locale.ENGLISH).format(monday).contains("Monday"));
        check("date style, German", true,
                FastDateFormat.getDateInstance(FastDateFormat.FULL, utc, Locale.GERMAN).format(monday).contains("Montag"));
        check("still cached", true,
                FastDateFormat.getInstance("EEEE", utc, Locale.GERMAN) == FastDateFormat.getInstance("EEEE", utc, Locale.GERMAN));
        System.exit(failures == 0 ? 0 : 1);
    }
}
EOF
javac -d "$hidden" -cp target/classes "$hidden/Hidden.java" && out=$(java -cp "target/classes:$hidden" Hidden) \
    || { echo "wrong behaviour: $(echo "$out" | head -3 | tr '\n' ';')"; exit 1; }
mvn -q -o -Dsurefire.failIfNoSpecifiedTests=false test -Dtest=FastDateFormatTest > "$log" 2>&1 \
    || { echo "tests fail: $(grep -m1 -E 'FAIL|ERROR' "$log")"; exit 1; }
changed=$(git status --porcelain -- . ':!*.java' ':!target')
[ -z "$changed" ] || { echo "non-Java files changed: $changed"; exit 1; }
echo "correct"
