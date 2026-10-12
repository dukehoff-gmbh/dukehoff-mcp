package com.kestrel.billing.rules;

import com.kestrel.billing.BillingContext;
import com.kestrel.billing.Money;

/**
 * One step of a tariff's pricing: takes the amount so far and returns the new amount. Rules are
 * created by name ({@link RuleFactory}) and applied in priority order ({@link RuleChain}).
 */
public interface Rule {

    Money apply(Money amount, BillingContext context);
}
