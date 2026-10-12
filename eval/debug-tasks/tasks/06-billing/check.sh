#!/usr/bin/env bash
#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

# run in the workspace made by ../../setup-bug.sh; exit 0 = correct
# A hidden program checks the reported invoice and the rule order of a hidden tariff with other
# two-digit priorities, so special-casing the business tariff is no fix.
log=${TMPDIR:-/tmp}/dukehoffmcp-debug-billing.log
mvn -q -o test-compile > "$log" 2>&1 || { echo "does not compile: $(grep -m1 ERROR "$log")"; exit 1; }
hidden=$(mktemp -d)
cat > "$hidden/tariffs-premium.properties" << 'EOF'
tariff.PREMIUM.rules=cap:100,base:2,loyalty:30,volume:11
EOF
cat > "$hidden/Hidden.java" << 'EOF'
import com.kestrel.billing.Customer;
import com.kestrel.billing.Invoice;
import com.kestrel.billing.InvoiceService;
import com.kestrel.billing.config.TariffConfig;
import com.kestrel.billing.rules.RuleChain;
import com.kestrel.billing.rules.RuleFactory;
import java.util.List;

public class Hidden {
    static int failures;

    static void check(String what, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            System.out.println(what + ": expected " + expected + ", got " + actual);
            failures++;
        }
    }

    public static void main(String[] args) {
        List<String> export = List.of("c-31;storage;800;GB", "c-31;traffic;400;GB", "c-31;compute;250;h",
                "c-50;storage;2;TB", "c-50;compute;1;h");
        InvoiceService service = new InvoiceService();
        Invoice c31 = service.invoice(new Customer("c-31", "BUSINESS", 5, false), export);
        check("c-31 net", "578.50", c31.net().toString());
        check("c-31 total", "688.42", c31.total().toString());
        Invoice c50 = service.invoice(new Customer("c-50", "BUSINESS", 3, false), export);
        check("c-50 net", "1352.10", c50.net().toString());
        check("business order", List.of("base", "volume", "loyalty", "cap"),
                RuleChain.of(TariffConfig.load("BUSINESS"), new RuleFactory()).names());
        check("premium order", List.of("base", "volume", "loyalty", "cap"),
                RuleChain.of(TariffConfig.load("PREMIUM"), new RuleFactory()).names());
        System.exit(failures == 0 ? 0 : 1);
    }
}
EOF
javac -d "$hidden" -cp target/classes "$hidden/Hidden.java" && out=$(java -cp "$hidden:target/classes" Hidden) \
    || { echo "wrong behaviour: $(echo "$out" | head -3 | tr '\n' ';')"; exit 1; }
mvn -q -o test > "$log" 2>&1 || { echo "tests fail: $(grep -m1 -E 'FAIL|ERROR' "$log")"; exit 1; }
changed=$(git status --porcelain -- . ':!*.java' ':!target')
[ -z "$changed" ] || { echo "non-Java files changed: $changed"; exit 1; }
echo "correct"
