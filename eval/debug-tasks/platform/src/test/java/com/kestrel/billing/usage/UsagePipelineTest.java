package com.kestrel.billing.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.kestrel.billing.time.BillingPeriod;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UsagePipelineTest {

    private static final List<String> EXPORT = List.of(
            "reading_id;customer;resource;quantity;unit;usage_day;recorded_at;kind",
            "R-1;c-1;storage;0.2;TB;2026-02-28;2026-03-01T02:00:00+01:00;reading",
            "R-2;c-1;storage;120;GB;2026-03-01;2026-03-02T02:00:00+01:00;reading",
            "R-3;c-1;compute;90;min;2026-03-02;2026-03-03T02:00:00+01:00;reading",
            "R-3;c-1;compute;90;min;2026-03-02;2026-03-03T02:00:00+01:00;reading",
            "R-4;c-2;api;2500;calls;2026-03-31;2026-04-01T03:00:00+02:00;reading",
            "R-1;c-1;storage;150;GB;2026-03-01;2026-03-04T09:00:00Z;correction",
            "R-5;c-2;traffic;3;GB;2026-04-01;2026-04-02T03:00:00+02:00;reading");

    @Test
    void correctionsMoveReadingsIntoThePeriod() {
        Map<String, UsageSummary> usage = new UsagePipeline().run(new ReadingParser().parse(EXPORT),
                BillingPeriod.parse("2026-03"));
        assertEquals(new BigDecimal("270.0000"), usage.get("c-1").of("storage"));
        assertEquals(new BigDecimal("1.5000"), usage.get("c-1").of("compute"));
        assertEquals(new BigDecimal("2.5000"), usage.get("c-2").of("api"));
        assertEquals(BigDecimal.ZERO, usage.get("c-2").of("traffic"));
    }
}
