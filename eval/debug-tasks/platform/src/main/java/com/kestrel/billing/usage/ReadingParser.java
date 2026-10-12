package com.kestrel.billing.usage;

import com.kestrel.billing.run.Csv;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the meter export: a header line, then
 * {@code reading_id;customer;resource;quantity;unit;usage_day;recorded_at;kind} with kind
 * {@code reading} or {@code correction}.
 */
public final class ReadingParser {

    public List<MeterReading> parse(List<String> lines) {
        List<MeterReading> readings = new ArrayList<>();
        for (Csv.Row row : Csv.rows(lines, 8)) {
            String kind = row.get(7);
            if (!kind.equals("reading") && !kind.equals("correction")) {
                throw new IllegalArgumentException(row.where() + ": unknown kind '" + kind + "'");
            }
            readings.add(new MeterReading(row.get(0), row.get(1), row.get(2), new BigDecimal(row.get(3)), row.get(4),
                    LocalDate.parse(row.get(5)), row.get(6), kind.equals("correction")));
        }
        return readings;
    }
}
