package com.kestrel.billing.rules;

import com.kestrel.billing.BillingContext;
import com.kestrel.billing.Money;

/** A fixed monthly credit for customers with a contract for some years. */
public final class LoyaltyRule implements Rule {

    @Override
    public Money apply(Money amount, BillingContext context) {
        if (context.customer().yearsActive() < context.config().integer("rule.loyalty.years")) {
            return amount;
        }
        return amount.minus(Money.of(context.config().decimal("rule.loyalty.amount"))).max(Money.ZERO);
    }
}
