package com.kestrel.billing.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class TariffConfigTest {

    @Test
    void baseValues() {
        TariffConfig standard = TariffConfig.load("STANDARD");
        assertEquals(new BigDecimal("100"), standard.decimal("rule.volume.threshold"));
        assertEquals(List.of(new RuleSpec("base", "1"), new RuleSpec("volume", "5"), new RuleSpec("loyalty", "7"),
                new RuleSpec("minimum", "90")), standard.rules());
    }

    @Test
    void overlayWins() {
        TariffConfig business = TariffConfig.load("BUSINESS");
        assertEquals(new BigDecimal("50"), business.decimal("rule.volume.threshold"));
        assertEquals(new BigDecimal("15"), business.decimal("rule.volume.percent"));
        assertEquals(new BigDecimal("25.00"), business.decimal("rule.loyalty.amount"));
    }
}
