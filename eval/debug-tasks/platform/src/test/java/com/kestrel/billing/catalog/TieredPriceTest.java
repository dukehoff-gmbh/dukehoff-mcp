package com.kestrel.billing.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.kestrel.billing.Money;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class TieredPriceTest {

    private final TieredPrice storage = TieredPrice.parse("0:0.80,500:0.65,2000:0.50");

    @Test
    void firstTierOnly() {
        assertEquals(Money.of("80.00"), storage.price(new BigDecimal("100")));
        assertEquals(1, storage.steps(new BigDecimal("100")).size());
    }

    @Test
    void graduated() {
        // 500 x 0.80 + 1500 x 0.65 + 500 x 0.50
        assertEquals(Money.of("1625.00"), storage.price(new BigDecimal("2500")));
    }

    @Test
    void exactlyOnABound() {
        assertEquals(Money.of("400.00"), storage.price(new BigDecimal("500")));
        assertEquals(1, storage.steps(new BigDecimal("500")).size());
    }

    @Test
    void nothing() {
        assertEquals(Money.ZERO, storage.price(BigDecimal.ZERO));
    }

    @Test
    void mustStartAtZero() {
        assertThrows(IllegalArgumentException.class, () -> TieredPrice.parse("10:0.5"));
    }
}
