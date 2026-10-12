package com.kestrel.billing.rules;

import com.kestrel.billing.BillingContext;
import com.kestrel.billing.Money;

/** A percentage off for students. */
public final class StudentRule implements Rule {

    @Override
    public Money apply(Money amount, BillingContext context) {
        if (!context.customer().student()) {
            return amount;
        }
        return amount.minus(amount.percent(context.config().decimal("rule.student.percent")));
    }
}
