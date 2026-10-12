package com.kestrel.billing.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Rounds an invoice total in its currency: to cents, and Swiss francs to 5 Rappen as the Swiss
 * customers pay in cash at the post office.
 */
public final class CurrencyRounding {

    private static final BigDecimal TWENTY = BigDecimal.valueOf(20);

    public BigDecimal round(BigDecimal amount, String currency) {
        if (currency.equals("CHF")) {
            return amount.multiply(TWENTY).setScale(0, RoundingMode.HALF_UP)
                    .divide(TWENTY, 2, RoundingMode.UNNECESSARY);
        }
        return amount.setScale(2, RoundingMode.HALF_EVEN);
    }

    /** {@code eur} euros in {@code currency} at {@code rate}, rounded. */
    public BigDecimal convert(BigDecimal eur, BigDecimal rate, String currency) {
        return round(eur.multiply(rate), currency);
    }
}
