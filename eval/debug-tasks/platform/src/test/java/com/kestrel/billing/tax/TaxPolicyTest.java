package com.kestrel.billing.tax;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.kestrel.billing.customer.Customer;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaxPolicyTest {

    private final TaxPolicy policy = new TaxPolicy(TaxTable.parse(List.of("country;rate;eu", "DE;19;true", "AT;20;true",
            "CH;8.1;false")));

    @Test
    void domestic() {
        assertEquals(new BigDecimal("19"), policy.decide(new Customer("c", "n", "DE", "EUR", "DE1", 0, false)).rate());
        assertEquals(new BigDecimal("19"), policy.decide(new Customer("c", "n", "DE", "EUR", "", 0, false)).rate());
    }

    @Test
    void reverseChargeForEuBusinesses() {
        TaxDecision d = policy.decide(new Customer("c", "n", "AT", "EUR", "ATU1", 0, false));
        assertEquals(BigDecimal.ZERO, d.rate());
        assertEquals("reverse charge, VAT id ATU1", d.note());
    }

    @Test
    void consumersAbroadPayTheirRate() {
        assertEquals(new BigDecimal("20"), policy.decide(new Customer("c", "n", "AT", "EUR", "", 0, false)).rate());
    }

    @Test
    void outsideTheEu() {
        assertEquals(new BigDecimal("8.1"), policy.decide(new Customer("c", "n", "CH", "CHF", "CHE1", 0, false)).rate());
    }
}
