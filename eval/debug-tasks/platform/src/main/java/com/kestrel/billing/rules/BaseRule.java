package com.kestrel.billing.rules;

import com.kestrel.billing.BillingContext;
import com.kestrel.billing.Money;

/** Adds the base fees of the period, prorated over tariff changes. */
public final class BaseRule implements Rule {

    @Override
    public Money apply(Money amount, BillingContext context) {
        return amount.plus(context.baseFee());
    }
}
