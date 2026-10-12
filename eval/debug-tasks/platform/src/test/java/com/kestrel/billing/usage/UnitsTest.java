package com.kestrel.billing.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class UnitsTest {

    private final Units units = new Units();

    private static MeterReading r(String resource, String quantity, String unit) {
        return new MeterReading("R", "c", resource, new BigDecimal(quantity), unit, LocalDate.of(2026, 3, 1),
                "2026-03-02T01:00:00+01:00", false);
    }

    @Test
    void gigabytes() {
        assertEquals(new BigDecimal("2500.0000"), units.toBillingUnit(r("traffic", "2.5", "TB")));
        assertEquals(new BigDecimal("0.9000"), units.toBillingUnit(r("storage", "900", "MB")));
    }

    @Test
    void hours() {
        assertEquals(new BigDecimal("120.0000"), units.toBillingUnit(r("compute", "7200", "min")));
        assertEquals(new BigDecimal("2.0000"), units.toBillingUnit(r("compute", "7200", "s")));
    }

    @Test
    void thousandsOfCalls() {
        assertEquals(new BigDecimal("12.3450"), units.toBillingUnit(r("api", "12345", "calls")));
    }

    @Test
    void unitMustFitResource() {
        assertThrows(IllegalArgumentException.class, () -> units.toBillingUnit(r("compute", "1", "GB")));
    }
}
