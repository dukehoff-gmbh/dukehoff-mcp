package com.kestrel.billing.rules;

import com.kestrel.billing.BillingContext;
import com.kestrel.billing.Money;
import java.math.BigDecimal;

/**
 * Enterprise commitments: the customer pays at least the committed monthly amount, and usage above it
 * gets the overage discount.
 */
public final class CommitmentRule implements Rule {

    @Override
    public Money apply(Money amount, BillingContext context) {
        Money committed = Money.of(context.config().decimal("rule.commitment.amount"));
        if (amount.compareTo(committed) <= 0) {
            return committed;
        }
        Money overage = amount.minus(committed);
        BigDecimal discount = context.config().decimal("rule.commitment.overage.discount");
        return committed.plus(overage.minus(overage.percent(discount)));
    }
}
