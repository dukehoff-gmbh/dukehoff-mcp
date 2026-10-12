package com.kestrel.billing.time;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Days from {@code start} (inclusive) to {@code end} (exclusive).
 */
public record DateRange(LocalDate start, LocalDate end) {

    public DateRange {
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("range ends before it starts: " + start + " - " + end);
        }
    }

    public long days() {
        return ChronoUnit.DAYS.between(start, end);
    }

    public boolean contains(LocalDate day) {
        return !day.isBefore(start) && day.isBefore(end);
    }

    /** The days both ranges cover, if any. */
    public Optional<DateRange> intersect(DateRange other) {
        LocalDate s = start.isAfter(other.start) ? start : other.start;
        LocalDate e = end.isBefore(other.end) ? end : other.end;
        return s.isBefore(e) ? Optional.of(new DateRange(s, e)) : Optional.empty();
    }

    @Override
    public String toString() {
        return start + ".." + end.minusDays(1);
    }
}
