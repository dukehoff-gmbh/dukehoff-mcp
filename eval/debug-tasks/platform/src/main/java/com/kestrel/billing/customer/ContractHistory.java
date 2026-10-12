package com.kestrel.billing.customer;

import com.kestrel.billing.time.BillingPeriod;
import com.kestrel.billing.time.DateRange;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The tariffs of one customer over time.
 */
public final class ContractHistory {

    /** A tariff and the days of a period it applied on. */
    public record Segment(String tariff, DateRange days) {
    }

    private final String customer;
    private final List<Contract> contracts;

    public ContractHistory(String customer, List<Contract> contracts) {
        this.customer = customer;
        List<Contract> sorted = new ArrayList<>(contracts);
        sorted.sort(Comparator.comparing(Contract::from));
        this.contracts = List.copyOf(sorted);
    }

    /** The tariff segments within the period, in time order; empty if there was no contract. */
    public List<Segment> segments(BillingPeriod period) {
        List<Segment> segments = new ArrayList<>();
        for (int i = 0; i < contracts.size(); i++) {
            Contract c = contracts.get(i);
            LocalDate until = i + 1 < contracts.size() ? contracts.get(i + 1).from() : LocalDate.MAX;
            new DateRange(c.from(), until).intersect(period.range())
                    .ifPresent(days -> segments.add(new Segment(c.tariff(), days)));
        }
        return segments;
    }

    /** The tariff on the last day of the period: it decides prices and rules. */
    public String tariffAtEnd(BillingPeriod period) {
        List<Segment> segments = segments(period);
        if (segments.isEmpty()) {
            throw new IllegalStateException("customer " + customer + " has no contract in " + period);
        }
        return segments.get(segments.size() - 1).tariff();
    }
}
