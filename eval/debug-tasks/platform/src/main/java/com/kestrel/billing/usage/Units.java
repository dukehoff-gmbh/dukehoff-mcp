package com.kestrel.billing.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;

/**
 * Converts metered quantities into the billing unit of their resource: GB for storage and traffic,
 * hours for compute, units for API calls (thousands). Decimal prefixes (1 GB = 1000 MB).
 */
public final class Units {

    private static final Map<String, String> BILLING_UNIT = Map.of("storage", "gb", "traffic", "gb", "compute", "h",
            "api", "k");
    private static final Map<String, BigDecimal> TO_GB = Map.of(
            "mb", new BigDecimal("0.001"), "gb", BigDecimal.ONE, "tb", new BigDecimal("1000"));
    private static final Map<String, BigDecimal> TO_HOURS = Map.of(
            "s", BigDecimal.ONE.divide(new BigDecimal("3600"), 10, RoundingMode.HALF_EVEN),
            "min", BigDecimal.ONE.divide(new BigDecimal("60"), 10, RoundingMode.HALF_EVEN),
            "h", BigDecimal.ONE);
    private static final Map<String, BigDecimal> TO_THOUSANDS = Map.of(
            "calls", new BigDecimal("0.001"), "k", BigDecimal.ONE);

    public String billingUnit(String resource) {
        String unit = BILLING_UNIT.get(resource);
        if (unit == null) {
            throw new IllegalArgumentException("unknown resource " + resource);
        }
        return unit;
    }

    public BigDecimal toBillingUnit(MeterReading r) {
        String target = billingUnit(r.resource());
        Map<String, BigDecimal> factors = switch (target) {
            case "h" -> TO_HOURS;
            case "k" -> TO_THOUSANDS;
            default -> TO_GB;
        };
        BigDecimal factor = factors.get(r.unit().toLowerCase(Locale.ROOT));
        if (factor == null) {
            throw new IllegalArgumentException("reading " + r.readingId() + ": unit " + r.unit()
                    + " does not fit resource " + r.resource());
        }
        return r.quantity().multiply(factor).setScale(4, RoundingMode.HALF_EVEN);
    }
}
