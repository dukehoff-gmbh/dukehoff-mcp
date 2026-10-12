package com.kestrel.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * An amount in euros, always with two decimals; every operation rounds half-even. Amounts in other
 * currencies exist only on the finished invoice ({@link com.kestrel.billing.money.CurrencyRounding}).
 */
public record Money(BigDecimal amount) implements Comparable<Money> {

    public static final Money ZERO = of("0");

    public Money {
        amount = amount.setScale(2, RoundingMode.HALF_EVEN);
    }

    public static Money of(String amount) {
        return new Money(new BigDecimal(amount));
    }

    public static Money of(BigDecimal amount) {
        return new Money(amount);
    }

    public Money plus(Money other) {
        return new Money(amount.add(other.amount));
    }

    public Money minus(Money other) {
        return new Money(amount.subtract(other.amount));
    }

    public Money times(BigDecimal factor) {
        return new Money(amount.multiply(factor));
    }

    /** {@code percent} percent of this amount. */
    public Money percent(BigDecimal percent) {
        return new Money(amount.multiply(percent).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_EVEN));
    }

    public Money min(Money other) {
        return compareTo(other) <= 0 ? this : other;
    }

    public Money max(Money other) {
        return compareTo(other) >= 0 ? this : other;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    @Override
    public int compareTo(Money other) {
        return amount.compareTo(other.amount);
    }

    @Override
    public String toString() {
        return amount.toPlainString();
    }
}
