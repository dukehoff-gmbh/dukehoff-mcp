package com.kestrel.billing.credit;

import com.kestrel.billing.Money;
import com.kestrel.billing.run.Csv;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Open credit balances ({@code credits.csv}: {@code id;customer;amount;expires}). Credits are used up
 * soonest-expiring first; what a run uses is gone for the next invoice of the run.
 */
public final class CreditLedger {

    /** The part of a credit one invoice used. */
    public record Use(String creditId, Money amount) {
    }

    private final Map<String, List<Credit>> byCustomer = new HashMap<>();
    private final Map<String, Money> remaining = new HashMap<>();

    public static CreditLedger parse(List<String> lines) {
        CreditLedger ledger = new CreditLedger();
        for (Csv.Row row : Csv.rows(lines, 4)) {
            Credit c = new Credit(row.get(0), row.get(1), Money.of(row.get(2)), LocalDate.parse(row.get(3)));
            ledger.byCustomer.computeIfAbsent(c.customer(), k -> new ArrayList<>()).add(c);
            ledger.remaining.put(c.id(), c.amount());
        }
        ledger.byCustomer.values().forEach(l -> l.sort(Comparator.comparing(Credit::expires).thenComparing(Credit::id)));
        return ledger;
    }

    /** Uses credits valid on {@code day} against {@code due}, at most {@code due}. */
    public List<Use> use(String customer, Money due, LocalDate day) {
        List<Use> uses = new ArrayList<>();
        Money open = due;
        for (Credit c : byCustomer.getOrDefault(customer, List.of())) {
            if (!open.isPositive()) {
                break;
            }
            if (c.expires().isBefore(day)) {
                continue;
            }
            Money left = remaining.get(c.id());
            Money take = left.min(open);
            if (take.isPositive()) {
                remaining.put(c.id(), left.minus(take));
                open = open.minus(take);
                uses.add(new Use(c.id(), take));
            }
        }
        return uses;
    }
}
