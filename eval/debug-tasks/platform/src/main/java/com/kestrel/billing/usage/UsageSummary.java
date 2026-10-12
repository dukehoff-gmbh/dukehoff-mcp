package com.kestrel.billing.usage;

import java.math.BigDecimal;
import java.util.Map;
import java.util.TreeMap;

/**
 * A customer's usage in a period: quantity per resource in billing units.
 */
public record UsageSummary(String customer, Map<String, BigDecimal> quantities) {

    public UsageSummary {
        quantities = new TreeMap<>(quantities);
    }

    public static UsageSummary empty(String customer) {
        return new UsageSummary(customer, Map.of());
    }

    public BigDecimal of(String resource) {
        return quantities.getOrDefault(resource, BigDecimal.ZERO);
    }
}
