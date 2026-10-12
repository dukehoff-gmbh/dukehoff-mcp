package com.kestrel.billing.rules;

import com.kestrel.billing.BillingContext;
import com.kestrel.billing.Money;

/** No invoice above the tariff's maximum. */
public final class CapRule implements Rule {

    @Override
    public Money apply(Money amount, BillingContext context) {
        return amount.min(Money.of(context.config().decimal("rule.cap.max")));
    }
}
