package com.kestrel.billing.run;

import java.util.ArrayList;
import java.util.List;

/**
 * The semicolon separated files of a data set: a header line, then rows. Blank lines and lines
 * starting with {@code #} are skipped.
 */
public final class Csv {

    /** One row with its line number for messages. */
    public record Row(int line, String[] fields) {

        public String get(int i) {
            return fields[i];
        }

        public String where() {
            return "line " + line;
        }
    }

    private Csv() {
    }

    public static List<Row> rows(List<String> lines, int columns) {
        List<Row> rows = new ArrayList<>();
        boolean header = true;
        int number = 0;
        for (String line : lines) {
            number++;
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            if (header) {
                header = false;
                continue;
            }
            String[] f = l.split(";", -1);
            if (f.length != columns) {
                throw new IllegalArgumentException("line " + number + ": expected " + columns + " fields, got "
                        + f.length + ": " + line);
            }
            for (int i = 0; i < f.length; i++) {
                f[i] = f[i].strip();
            }
            rows.add(new Row(number, f));
        }
        return rows;
    }
}
