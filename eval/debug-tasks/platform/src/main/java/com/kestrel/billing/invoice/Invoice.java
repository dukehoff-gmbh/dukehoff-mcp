package com.kestrel.billing.invoice;

import com.kestrel.billing.Money;
import com.kestrel.billing.time.BillingPeriod;
import java.math.BigDecimal;
import java.util.List;

/**
 * A finished invoice. Amounts are in euros except {@code total}, which is in the customer's currency.
 *
 * @param usageCharges the priced usage before rules
 * @param net after the tariff's rules
 * @param credits credits used against {@code net + tax}
 * @param rate units of {@code currency} per euro
 * @param total what the customer pays, in {@code currency}
 */
public record Invoice(String customer, BillingPeriod period, String tariff, String currency, Money usageCharges,
        Money net, Money tax, Money credits, BigDecimal rate, BigDecimal total, List<InvoiceLine> lines) {
}
