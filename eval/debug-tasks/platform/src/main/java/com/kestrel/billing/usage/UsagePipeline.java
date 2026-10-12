package com.kestrel.billing.usage;

import com.kestrel.billing.time.BillingPeriod;
import java.util.List;
import java.util.Map;

/**
 * From the raw meter export to usage per customer: corrections first (a correction may move a
 * reading to another day), then the period, then the sums.
 */
public final class UsagePipeline {

    private final CorrectionMerger corrections = new CorrectionMerger();
    private final PeriodFilter filter = new PeriodFilter();
    private final UsageAggregator aggregator = new UsageAggregator(new Units());

    public Map<String, UsageSummary> run(List<MeterReading> export, BillingPeriod period) {
        List<MeterReading> merged = corrections.merge(export);
        List<MeterReading> inPeriod = filter.apply(merged, period);
        return aggregator.aggregate(inPeriod);
    }
}
