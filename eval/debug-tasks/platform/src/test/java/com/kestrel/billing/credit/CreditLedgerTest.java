package com.kestrel.billing.credit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.kestrel.billing.Money;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CreditLedgerTest {

    private final LocalDate end = LocalDate.of(2026, 3, 31);

    @Test
    void soonestExpiringFirstAndExpiredSkipped() {
        CreditLedger ledger = CreditLedger.parse(List.of("id;customer;amount;expires", "A;c;50.00;2026-12-28",
                "B;c;10.00;2026-04-28", "C;c;99.00;2026-02-28"));
        assertEquals(List.of(new CreditLedger.Use("B", Money.of("10.00")), new CreditLedger.Use("A", Money.of("20.00"))),
                ledger.use("c", Money.of("30.00"), end));
        assertEquals(List.of(new CreditLedger.Use("A", Money.of("30.00"))), ledger.use("c", Money.of("80.00"), end));
        assertEquals(List.of(), ledger.use("c", Money.of("80.00"), end));
    }

    @Test
    void noCredits() {
        assertEquals(List.of(), CreditLedger.parse(List.of("id;customer;amount;expires")).use("x", Money.of("5"), end));
    }
}
