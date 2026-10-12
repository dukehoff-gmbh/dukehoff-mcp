package com.kestrel.billing.usage;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Sums readings per customer and resource, in billing units.
 */
public final class UsageAggregator {

    private final Units units;

    public UsageAggregator(Units units) {
        this.units = units;
    }

    public Map<String, UsageSummary> aggregate(List<MeterReading> readings) {
        Map<String, Map<String, BigDecimal>> sums = new TreeMap<>();
        for (MeterReading r : readings) {
            sums.computeIfAbsent(r.customer(), c -> new HashMap<>())
                    .merge(r.resource(), units.toBillingUnit(r), BigDecimal::add);
        }
        Map<String, UsageSummary> summaries = new TreeMap<>();
        sums.forEach((customer, q) -> summaries.put(customer, new UsageSummary(customer, q)));
        return summaries;
    }
}
