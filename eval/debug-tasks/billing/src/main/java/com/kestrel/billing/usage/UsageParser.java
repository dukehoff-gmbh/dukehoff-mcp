package com.kestrel.billing.usage;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses usage exports: one record per line, {@code customer;resource;quantity;unit}. Blank lines and
 * lines starting with {@code #} are skipped.
 */
public final class UsageParser {

    public List<UsageRecord> parse(List<String> lines) {
        List<UsageRecord> records = new ArrayList<>();
        int number = 0;
        for (String line : lines) {
            number++;
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            String[] f = l.split(";");
            if (f.length != 4) {
                throw new IllegalArgumentException("line " + number + ": expected 4 fields, got " + f.length + ": " + line);
            }
            records.add(new UsageRecord(f[0].strip(), f[1].strip(), new BigDecimal(f[2].strip()), f[3].strip()));
        }
        return records;
    }
}
