package com.kestrel.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void roundsHalfEven() {
        assertEquals("0.12", Money.of("0.125").toString());
        assertEquals("0.14", Money.of("0.135").toString());
    }

    @Test
    void percent() {
        assertEquals("10.65", Money.of("71.00").percent(new BigDecimal("15")).toString());
        assertEquals("47.13", Money.of("248.06").percent(new BigDecimal("19")).toString());
    }

    @Test
    void minMax() {
        assertEquals(Money.of("3"), Money.of("3").min(Money.of("4")));
        assertEquals(Money.of("4"), Money.of("3").max(Money.of("4")));
    }
}
