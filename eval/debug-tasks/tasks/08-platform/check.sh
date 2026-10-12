#!/usr/bin/env bash
#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

# run in the workspace made by ../../setup-platform-bug.sh; exit 0 = correct
# A hidden program runs March against its own copy of the reference totals and merges two
# corrections written in different UTC offsets, so special-casing c-1187 or "corrections always win"
# is no fix.
log=${TMPDIR:-/tmp}/dukehoffmcp-debug-platform.log
mvn -q -o test-compile > "$log" 2>&1 || { echo "does not compile: $(grep -m1 ERROR "$log")"; exit 1; }
hidden=$(mktemp -d)
cat > "$hidden/Hidden.java" << 'EOF'
import com.kestrel.billing.invoice.Invoice;
import com.kestrel.billing.run.BillingRun;
import com.kestrel.billing.run.DataSet;
import com.kestrel.billing.time.BillingPeriod;
import com.kestrel.billing.usage.CorrectionMerger;
import com.kestrel.billing.usage.MeterReading;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

public class Hidden {
    static int failures;

    static void check(String what, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            System.out.println(what + ": expected " + expected + ", got " + actual);
            failures++;
        }
    }

    static MeterReading r(String quantity, String recordedAt, boolean correction) {
        return new MeterReading("R-9", "c-9", "storage", new BigDecimal(quantity), "GB", LocalDate.of(2026, 3, 21),
                recordedAt, correction);
    }

    public static void main(String[] args) throws Exception {
        List<String> reference = Files.readAllLines(Path.of(args[0]));
        List<Invoice> invoices = new BillingRun(DataSet.load(Path.of("data/2026-03"))).run(BillingPeriod.parse("2026-03"));
        check("invoices", reference.size(), invoices.size());
        for (int i = 0; i < Math.min(reference.size(), invoices.size()); i++) {
            Invoice inv = invoices.get(i);
            check("invoice " + i, reference.get(i), inv.customer() + ";" + inv.currency() + ";" + inv.total().toPlainString());
        }
        MeterReading reading = r("10", "2026-03-22T03:00:00+01:00", false);
        MeterReading first = r("8", "2026-03-22T10:00:00+01:00", true);   // 09:00 UTC
        MeterReading second = r("9", "2026-03-22T09:30:00Z", true);       // 09:30 UTC
        check("later correction in UTC", List.of(second), new CorrectionMerger().merge(List.of(reading, first, second)));
        check("later correction in UTC, other order", List.of(second),
                new CorrectionMerger().merge(List.of(second, reading, first)));
        System.exit(failures == 0 ? 0 : 1);
    }
}
EOF
# the reference totals as committed (the agent must not change them; this copy is independent)
git show HEAD:src/test/resources/reference/2026-03-totals.csv | tail -n +2 > "$hidden/reference.csv"
javac -d "$hidden" -cp target/classes "$hidden/Hidden.java" && out=$(java -cp "target/classes:$hidden" Hidden "$hidden/reference.csv") \
    || { echo "wrong behaviour: $(echo "$out" | head -3 | tr '\n' ';')"; exit 1; }
mvn -q -o test > "$log" 2>&1 || { echo "tests fail: $(grep -m1 -E 'FAIL|ERROR' "$log")"; exit 1; }
changed=$(git status --porcelain -- . ':!*.java' ':!target')
[ -z "$changed" ] || { echo "non-Java files changed: $changed"; exit 1; }
echo "correct"
