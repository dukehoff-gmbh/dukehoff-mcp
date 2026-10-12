package com.kestrel.billing.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CorrectionMergerTest {

    private static MeterReading reading(String id, String quantity, String recordedAt, boolean correction) {
        return new MeterReading(id, "c-1", "storage", new BigDecimal(quantity), "GB", LocalDate.of(2026, 3, 4),
                recordedAt, correction);
    }

    @Test
    void redeliveryCountsOnce() {
        MeterReading r = reading("R-1", "10", "2026-03-05T02:10:00+01:00", false);
        assertEquals(List.of(r), new CorrectionMerger().merge(List.of(r, r)));
    }

    @Test
    void laterCorrectionWins() {
        MeterReading r = reading("R-1", "10", "2026-03-05T02:10:00+01:00", false);
        MeterReading c = reading("R-1", "8", "2026-03-07T10:00:00Z", true);
        assertEquals(List.of(c), new CorrectionMerger().merge(List.of(r, c)));
        assertEquals(List.of(c), new CorrectionMerger().merge(List.of(c, r)));
    }

    @Test
    void secondCorrectionWins() {
        MeterReading r = reading("R-1", "10", "2026-03-05T02:10:00+01:00", false);
        MeterReading c1 = reading("R-1", "8", "2026-03-07T10:00:00Z", true);
        MeterReading c2 = reading("R-1", "9", "2026-03-09T16:30:00Z", true);
        assertEquals(List.of(c2), new CorrectionMerger().merge(List.of(r, c2, c1)));
    }

    @Test
    void otherReadingsStay() {
        MeterReading a = reading("R-1", "10", "2026-03-05T02:10:00+01:00", false);
        MeterReading b = reading("R-2", "3", "2026-03-05T02:20:00+01:00", false);
        assertEquals(List.of(a, b), new CorrectionMerger().merge(List.of(a, b)));
    }
}
