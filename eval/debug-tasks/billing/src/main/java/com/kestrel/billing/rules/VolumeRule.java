package com.kestrel.billing.rules;

import com.kestrel.billing.BillingContext;
import com.kestrel.billing.Money;

/** A percentage off the whole amount for customers storing more than the threshold (GB). */
public final class VolumeRule implements Rule {

    @Override
    public Money apply(Money amount, BillingContext context) {
        if (context.usageOf("storage").compareTo(context.config().decimal("rule.volume.threshold")) <= 0) {
            return amount;
        }
        return amount.minus(amount.percent(context.config().decimal("rule.volume.percent")));
    }
}
