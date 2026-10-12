package com.kestrel.billing.time;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * A calendar month as billed. Usage belongs to the period of its usage day, which meters report in
 * the customer's local calendar, so no time zone is involved here.
 */
public record BillingPeriod(YearMonth month) {

    public static BillingPeriod parse(String text) {
        return new BillingPeriod(YearMonth.parse(text));
    }

    public DateRange range() {
        return new DateRange(month.atDay(1), month.plusMonths(1).atDay(1));
    }

    public LocalDate firstDay() {
        return month.atDay(1);
    }

    public LocalDate lastDay() {
        return month.atEndOfMonth();
    }

    public long days() {
        return month.lengthOfMonth();
    }

    public boolean contains(LocalDate day) {
        return range().contains(day);
    }

    @Override
    public String toString() {
        return month.toString();
    }
}
