package com.kestrel.billing.customer;

import com.kestrel.billing.Money;
import com.kestrel.billing.config.TariffConfig;
import com.kestrel.billing.time.BillingPeriod;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.function.Function;

/**
 * Base fees for a period: each tariff's monthly fee for the share of days it applied.
 */
public final class Proration {

    private final Function<String, TariffConfig> configs;

    public Proration(Function<String, TariffConfig> configs) {
        this.configs = configs;
    }

    public Money baseFee(ContractHistory history, BillingPeriod period) {
        Money fee = Money.ZERO;
        for (ContractHistory.Segment s : history.segments(period)) {
            BigDecimal monthly = configs.apply(s.tariff()).decimal("rule.base.fee");
            BigDecimal share = BigDecimal.valueOf(s.days().days())
                    .divide(BigDecimal.valueOf(period.days()), 10, RoundingMode.HALF_EVEN);
            fee = fee.plus(Money.of(monthly.multiply(share)));
        }
        return fee;
    }
}
