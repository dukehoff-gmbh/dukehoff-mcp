package com.kestrel.billing.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.kestrel.billing.config.TariffConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleChainTest {

    @Test
    void standardOrder() {
        assertEquals(List.of("base", "volume", "loyalty", "minimum"),
                RuleChain.of(TariffConfig.load("STANDARD"), new RuleFactory()).names());
    }

    @Test
    void studentOrder() {
        assertEquals(List.of("base", "student", "minimum"),
                RuleChain.of(TariffConfig.load("STUDENT"), new RuleFactory()).names());
    }

    @Test
    void unknownRule() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> new RuleFactory().create("bonus"));
        assertEquals("no rule 'bonus' (com.kestrel.billing.rules.BonusRule)", ex.getMessage());
    }
}
