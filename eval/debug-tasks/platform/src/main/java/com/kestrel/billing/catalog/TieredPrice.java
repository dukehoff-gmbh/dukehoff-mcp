package com.kestrel.billing.catalog;

import com.kestrel.billing.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Graduated prices: the first units at the first tier's price, the units above the next bound at the
 * next price, and so on. Written as {@code from:price,...}, e.g. {@code 0:0.80,500:0.65}.
 */
public final class TieredPrice {

    /** The part of a quantity that fell into one tier. */
    public record Step(Tier tier, BigDecimal quantity, Money amount) {
    }

    private final List<Tier> tiers;

    public TieredPrice(List<Tier> tiers) {
        if (tiers.isEmpty() || tiers.get(0).from().signum() != 0) {
            throw new IllegalArgumentException("the first tier must start at 0: " + tiers);
        }
        List<Tier> sorted = new ArrayList<>(tiers);
        sorted.sort(Comparator.comparing(Tier::from));
        this.tiers = List.copyOf(sorted);
    }

    public static TieredPrice parse(String text) {
        List<Tier> tiers = new ArrayList<>();
        for (String part : text.split(",")) {
            String[] p = part.strip().split(":");
            if (p.length != 2) {
                throw new IllegalArgumentException("tier '" + part + "' is not from:price");
            }
            tiers.add(new Tier(new BigDecimal(p[0].strip()), new BigDecimal(p[1].strip())));
        }
        return new TieredPrice(tiers);
    }

    public List<Step> steps(BigDecimal quantity) {
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < tiers.size(); i++) {
            Tier t = tiers.get(i);
            if (quantity.compareTo(t.from()) <= 0) {
                break;
            }
            BigDecimal upper = i + 1 < tiers.size() ? tiers.get(i + 1).from().min(quantity) : quantity;
            BigDecimal q = upper.subtract(t.from());
            steps.add(new Step(t, q, Money.of(q.multiply(t.unitPrice()))));
        }
        return steps;
    }

    public Money price(BigDecimal quantity) {
        Money sum = Money.ZERO;
        for (Step s : steps(quantity)) {
            sum = sum.plus(s.amount());
        }
        return sum;
    }

    public List<Tier> tiers() {
        return tiers;
    }
}
