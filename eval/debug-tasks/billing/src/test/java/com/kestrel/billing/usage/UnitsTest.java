package com.kestrel.billing.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class UnitsTest {

    private final Units units = new Units();

    @Test
    void storageInGigabytes() {
        assertEquals(new BigDecimal("2500.0000"), units.toBillingUnit(new UsageRecord("c", "traffic", new BigDecimal("2.5"), "TB")));
        assertEquals(new BigDecimal("0.9000"), units.toBillingUnit(new UsageRecord("c", "traffic", new BigDecimal("900"), "MB")));
    }

    @Test
    void computeInHours() {
        assertEquals(new BigDecimal("120.0000"), units.toBillingUnit(new UsageRecord("c", "compute", new BigDecimal("7200"), "min")));
        assertEquals(new BigDecimal("2.0000"), units.toBillingUnit(new UsageRecord("c", "compute", new BigDecimal("7200"), "s")));
    }

    @Test
    void unitMustFitResource() {
        assertThrows(IllegalArgumentException.class, () -> units.toBillingUnit(new UsageRecord("c", "compute", BigDecimal.ONE, "GB")));
    }
}
