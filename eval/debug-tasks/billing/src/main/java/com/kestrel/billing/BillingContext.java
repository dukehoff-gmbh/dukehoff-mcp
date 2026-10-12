package com.kestrel.billing;

import com.kestrel.billing.config.TariffConfig;
import java.math.BigDecimal;
import java.util.Map;

/**
 * What the rules may look at while pricing one invoice.
 *
 * @param usage quantity per resource, in billing units
 */
public record BillingContext(Customer customer, Map<String, BigDecimal> usage, TariffConfig config) {

    public BigDecimal usageOf(String resource) {
        return usage.getOrDefault(resource, BigDecimal.ZERO);
    }
}
