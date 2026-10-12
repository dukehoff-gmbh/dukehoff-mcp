package com.kestrel.billing.money;

import com.kestrel.billing.run.Csv;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Reference rates of the central bank ({@code rates.csv}: {@code date;currency;rate}, units of the
 * currency per euro). Weekends and holidays have no rate: the last rate before counts.
 */
public final class ExchangeRates {

    private final Map<String, NavigableMap<LocalDate, BigDecimal>> rates = new HashMap<>();

    public static ExchangeRates parse(java.util.List<String> lines) {
        ExchangeRates r = new ExchangeRates();
        for (Csv.Row row : Csv.rows(lines, 3)) {
            r.rates.computeIfAbsent(row.get(1), c -> new TreeMap<>())
                    .put(LocalDate.parse(row.get(0)), new BigDecimal(row.get(2)));
        }
        return r;
    }

    /** Units of {@code currency} per euro on {@code day}. */
    public BigDecimal rate(String currency, LocalDate day) {
        if (currency.equals("EUR")) {
            return BigDecimal.ONE;
        }
        NavigableMap<LocalDate, BigDecimal> series = rates.get(currency);
        Map.Entry<LocalDate, BigDecimal> e = series == null ? null : series.floorEntry(day);
        if (e == null) {
            throw new IllegalArgumentException("no " + currency + " rate on or before " + day);
        }
        return e.getValue();
    }
}
