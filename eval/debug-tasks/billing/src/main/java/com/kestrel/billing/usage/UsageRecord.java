package com.kestrel.billing.usage;

import java.math.BigDecimal;

/**
 * One line of a usage export: how much of a resource a customer used, in the unit the meter reported.
 */
public record UsageRecord(String customer, String resource, BigDecimal quantity, String unit) {
}
