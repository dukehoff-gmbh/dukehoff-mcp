package com.kestrel.billing.tax;

import com.kestrel.billing.run.Csv;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Standard VAT rates per country and whether the country is in the EU ({@code tax-rates.csv}:
 * {@code country;rate;eu}).
 */
public final class TaxTable {

    private record Entry(BigDecimal rate, boolean eu) {
    }

    private final Map<String, Entry> entries = new TreeMap<>();

    public static TaxTable parse(List<String> lines) {
        TaxTable table = new TaxTable();
        for (Csv.Row row : Csv.rows(lines, 3)) {
            table.entries.put(row.get(0), new Entry(new BigDecimal(row.get(1)), Boolean.parseBoolean(row.get(2))));
        }
        return table;
    }

    private Entry entry(String country) {
        Entry e = entries.get(country);
        if (e == null) {
            throw new IllegalArgumentException("no tax rate for " + country);
        }
        return e;
    }

    public BigDecimal rate(String country) {
        return entry(country).rate();
    }

    public boolean isEu(String country) {
        return entry(country).eu();
    }
}
