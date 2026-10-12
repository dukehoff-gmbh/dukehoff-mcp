package com.kestrel.billing.tax;

import java.math.BigDecimal;

/**
 * The tax treatment of one invoice.
 *
 * @param rate percent
 * @param note printed on the invoice, e.g. "reverse charge"
 */
public record TaxDecision(BigDecimal rate, String note) {
}
