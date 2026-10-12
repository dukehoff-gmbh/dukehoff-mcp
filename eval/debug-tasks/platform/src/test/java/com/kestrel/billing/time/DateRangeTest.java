package com.kestrel.billing.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DateRangeTest {

    private final DateRange march = BillingPeriod.parse("2026-03").range();

    @Test
    void endIsExclusive() {
        assertEquals(31, march.days());
        assertTrue(march.contains(LocalDate.of(2026, 3, 31)));
        assertFalse(march.contains(LocalDate.of(2026, 4, 1)));
    }

    @Test
    void intersect() {
        DateRange r = new DateRange(LocalDate.of(2026, 3, 10), LocalDate.MAX);
        assertEquals(Optional.of(new DateRange(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 4, 1))), r.intersect(march));
        assertEquals(Optional.empty(), new DateRange(LocalDate.of(2026, 4, 1), LocalDate.MAX).intersect(march));
    }
}
