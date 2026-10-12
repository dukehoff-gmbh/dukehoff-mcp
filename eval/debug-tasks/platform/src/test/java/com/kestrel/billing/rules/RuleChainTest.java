package com.kestrel.billing.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.kestrel.billing.BillingContext;
import com.kestrel.billing.Money;
import com.kestrel.billing.config.TariffConfig;
import com.kestrel.billing.customer.Customer;
import com.kestrel.billing.time.BillingPeriod;
import com.kestrel.billing.usage.UsageSummary;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuleChainTest {

    private static BillingContext context(String tariff, Customer customer, String storage) {
        TariffConfig config = TariffConfig.load(tariff);
        return new BillingContext(customer, BillingPeriod.parse("2026-03"),
                new UsageSummary(customer.id(), Map.of("storage", new BigDecimal(storage))), config,
                Money.of(config.decimal("rule.base.fee")));
    }

    @Test
    void order() {
        assertEquals(List.of("base", "volume", "loyalty", "minimum"),
                RuleChain.of(TariffConfig.load("STANDARD"), new RuleFactory()).names());
        assertEquals(List.of("base", "volume", "loyalty", "cap"),
                RuleChain.of(TariffConfig.load("BUSINESS"), new RuleFactory()).names());
    }

    @Test
    void businessVolumeThenLoyalty() {
        Customer c = new Customer("c", "Acme", "DE", "EUR", "DE123", 5, false);
        // (1000 + 49) - 12 % - 25
        RuleChain.Outcome o = RuleChain.of(TariffConfig.load("BUSINESS"), new RuleFactory())
                .apply(Money.of("1000.00"), context("BUSINESS", c, "800"));
        assertEquals(Money.of("898.12"), o.amount());
        assertEquals(3, o.adjustments().size());
    }

    @Test
    void enterpriseCommitment() {
        Customer c = new Customer("c", "Acme", "DE", "EUR", "DE123", 1, false);
        RuleChain chain = RuleChain.of(TariffConfig.load("ENTERPRISE"), new RuleFactory());
        assertEquals(Money.of("2000.00"), chain.apply(Money.of("900.00"), context("ENTERPRISE", c, "10")).amount());
        // 2000 + (3250 - 2000) x 0.8
        assertEquals(Money.of("3000.00"), chain.apply(Money.of("3000.00"), context("ENTERPRISE", c, "10")).amount());
    }

    @Test
    void studentMinimum() {
        Customer c = new Customer("c", "Kim", "DE", "EUR", "", 0, true);
        assertEquals(Money.of("6.30"), RuleChain.of(TariffConfig.load("STUDENT"), new RuleFactory())
                .apply(Money.ZERO, context("STUDENT", c, "0")).amount());
    }

    @Test
    void unknownRule() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> new RuleFactory().create("bonus"));
        assertEquals("no rule 'bonus' (com.kestrel.billing.rules.BonusRule)", ex.getMessage());
    }
}
