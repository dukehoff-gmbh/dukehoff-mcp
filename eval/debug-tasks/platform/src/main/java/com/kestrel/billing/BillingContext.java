package com.kestrel.billing;

import com.kestrel.billing.config.TariffConfig;
import com.kestrel.billing.customer.Customer;
import com.kestrel.billing.time.BillingPeriod;
import com.kestrel.billing.usage.UsageSummary;

/**
 * What the rules may look at while pricing one invoice.
 *
 * @param config the configuration of the tariff at the end of the period
 * @param baseFee the prorated base fees of the period
 */
public record BillingContext(Customer customer, BillingPeriod period, UsageSummary usage, TariffConfig config,
        Money baseFee) {
}
