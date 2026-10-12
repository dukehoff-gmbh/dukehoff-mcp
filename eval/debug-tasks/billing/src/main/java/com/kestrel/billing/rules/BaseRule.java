package com.kestrel.billing.rules;

import com.kestrel.billing.BillingContext;
import com.kestrel.billing.Money;

/** Adds the tariff's monthly base fee. */
public final class BaseRule implements Rule {

    @Override
    public Money apply(Money amount, BillingContext context) {
        return amount.plus(Money.of(context.config().decimal("rule.base.fee")));
    }
}
