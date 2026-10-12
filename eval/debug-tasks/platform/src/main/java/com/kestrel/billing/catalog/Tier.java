package com.kestrel.billing.catalog;

import java.math.BigDecimal;

/**
 * A price step: every unit above {@code from} (up to the next tier) costs {@code unitPrice}.
 */
public record Tier(BigDecimal from, BigDecimal unitPrice) {
}
