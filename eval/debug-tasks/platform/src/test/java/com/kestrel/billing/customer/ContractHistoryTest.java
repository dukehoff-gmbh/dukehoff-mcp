package com.kestrel.billing.customer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.kestrel.billing.Money;
import com.kestrel.billing.config.TariffConfig;
import com.kestrel.billing.time.BillingPeriod;
import com.kestrel.billing.time.DateRange;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContractHistoryTest {

    private final BillingPeriod march = BillingPeriod.parse("2026-03");

    @Test
    void oneTariffAllMonth() {
        ContractHistory h = new ContractHistory("c", List.of(new Contract("c", "STANDARD", LocalDate.of(2024, 5, 1))));
        assertEquals(List.of(new ContractHistory.Segment("STANDARD", march.range())), h.segments(march));
        assertEquals(Money.of("20.00"), new Proration(TariffConfig::load).baseFee(h, march));
    }

    @Test
    void switchInTheMonth() {
        ContractHistory h = new ContractHistory("c", List.of(new Contract("c", "BUSINESS", LocalDate.of(2026, 3, 10)),
                new Contract("c", "STANDARD", LocalDate.of(2022, 2, 1))));
        assertEquals(List.of(
                new ContractHistory.Segment("STANDARD", new DateRange(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 10))),
                new ContractHistory.Segment("BUSINESS", new DateRange(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 4, 1)))),
                h.segments(march));
        assertEquals("BUSINESS", h.tariffAtEnd(march));
        // 9/31 x 20.00 + 22/31 x 49.00
        assertEquals(Money.of("40.58"), new Proration(TariffConfig::load).baseFee(h, march));
    }

    @Test
    void startedInTheMonth() {
        ContractHistory h = new ContractHistory("c", List.of(new Contract("c", "STUDENT", LocalDate.of(2026, 3, 16))));
        assertEquals(Money.of("4.65"), new Proration(TariffConfig::load).baseFee(h, march));
    }
}
