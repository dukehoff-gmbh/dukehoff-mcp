package com.kestrel.billing.rules;

import com.kestrel.billing.BillingContext;
import com.kestrel.billing.Money;

/** No invoice below the tariff's minimum. */
public final class MinimumRule implements Rule {

    @Override
    public Money apply(Money amount, BillingContext context) {
        return amount.max(Money.of(context.config().decimal("rule.minimum.amount")));
    }
}
