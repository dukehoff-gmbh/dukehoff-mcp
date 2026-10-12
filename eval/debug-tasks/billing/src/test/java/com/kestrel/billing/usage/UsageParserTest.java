package com.kestrel.billing.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class UsageParserTest {

    @Test
    void skipsCommentsAndBlankLines() {
        List<UsageRecord> records = new UsageParser().parse(List.of("# header", "", " c-1 ; storage ; 12.5 ; GB "));
        assertEquals(List.of(new UsageRecord("c-1", "storage", new BigDecimal("12.5"), "GB")), records);
    }

    @Test
    void rejectsShortLines() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new UsageParser().parse(List.of("c-1;storage;12")));
        assertEquals("line 1: expected 4 fields, got 3: c-1;storage;12", ex.getMessage());
    }
}
