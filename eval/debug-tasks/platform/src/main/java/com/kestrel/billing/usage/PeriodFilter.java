package com.kestrel.billing.usage;

import com.kestrel.billing.time.BillingPeriod;
import java.util.List;

/**
 * Keeps the readings whose usage day lies in the period. The export overlaps neighbouring months by a
 * few days so that late readings are not lost.
 */
public final class PeriodFilter {

    public List<MeterReading> apply(List<MeterReading> readings, BillingPeriod period) {
        return readings.stream().filter(r -> period.contains(r.usageDay())).toList();
    }
}
