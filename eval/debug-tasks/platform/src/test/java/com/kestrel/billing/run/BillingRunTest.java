package com.kestrel.billing.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kestrel.billing.invoice.Invoice;
import com.kestrel.billing.time.BillingPeriod;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The March 2026 run against accounting's reference totals (checked by hand from the tariff sheet).
 */
class BillingRunTest {

    private static List<Invoice> invoices;

    @BeforeAll
    static void run() {
        invoices = new BillingRun(DataSet.load(Path.of("data/2026-03"))).run(BillingPeriod.parse("2026-03"));
    }

    private static Map<String, String> reference() throws IOException {
        Map<String, String> totals = new TreeMap<>();
        try (InputStream in = BillingRunTest.class.getResourceAsStream("/reference/2026-03-totals.csv")) {
            List<String> lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            for (Csv.Row row : Csv.rows(lines, 3)) {
                totals.put(row.get(0), row.get(2) + " " + row.get(1));
            }
        }
        return totals;
    }

    @Test
    void everyCustomerIsInvoiced() {
        assertEquals(120, invoices.size());
    }

    @Test
    void totalsMatchReference() throws IOException {
        Map<String, String> expected = reference();
        List<String> wrong = new ArrayList<>();
        for (Invoice invoice : invoices) {
            String actual = invoice.total().toPlainString() + " " + invoice.currency();
            if (!actual.equals(expected.get(invoice.customer()))) {
                wrong.add(invoice.customer() + ": expected " + expected.get(invoice.customer()) + " but was " + actual);
            }
        }
        assertTrue(wrong.isEmpty(), "invoices differing from the reference:\n" + String.join("\n", wrong));
    }

    @Test
    void swissTotalsAreCashRounded() {
        for (Invoice invoice : invoices) {
            if (invoice.currency().equals("CHF")) {
                assertEquals(0, invoice.total().movePointRight(2).remainder(java.math.BigDecimal.valueOf(5)).signum(),
                        invoice.customer() + " " + invoice.total());
            }
        }
    }
}
