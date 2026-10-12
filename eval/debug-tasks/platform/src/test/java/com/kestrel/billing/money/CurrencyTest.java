package com.kestrel.billing.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CurrencyTest {

    private final CurrencyRounding rounding = new CurrencyRounding();

    @Test
    void francsToFiveRappen() {
        assertEquals(new BigDecimal("10.05"), rounding.round(new BigDecimal("10.026"), "CHF"));
        assertEquals(new BigDecimal("10.00"), rounding.round(new BigDecimal("10.024"), "CHF"));
        assertEquals(new BigDecimal("10.03"), rounding.round(new BigDecimal("10.026"), "EUR"));
    }

    @Test
    void lastRateBeforeTheWeekend() {
        ExchangeRates rates = ExchangeRates.parse(List.of("date;currency;rate", "2026-03-27;CHF;0.9400",
                "2026-03-30;CHF;0.9500"));
        assertEquals(new BigDecimal("0.9400"), rates.rate("CHF", LocalDate.of(2026, 3, 29)));
        assertEquals(BigDecimal.ONE, rates.rate("EUR", LocalDate.of(2026, 3, 29)));
        assertThrows(IllegalArgumentException.class, () -> rates.rate("CHF", LocalDate.of(2026, 3, 1)));
    }
}
