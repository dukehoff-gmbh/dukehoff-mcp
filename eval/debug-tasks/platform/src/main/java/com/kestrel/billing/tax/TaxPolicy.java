package com.kestrel.billing.tax;

import com.kestrel.billing.customer.Customer;
import java.math.BigDecimal;

/**
 * Which VAT an invoice carries. The seller is in {@link #SELLER_COUNTRY}. Businesses in other EU
 * countries get reverse charge; everyone else pays the rate of their own country (we are registered
 * for VAT in every country we sell to).
 */
public final class TaxPolicy {

    public static final String SELLER_COUNTRY = "DE";

    private final TaxTable table;

    public TaxPolicy(TaxTable table) {
        this.table = table;
    }

    public TaxDecision decide(Customer customer) {
        String country = customer.country();
        if (customer.isBusiness() && table.isEu(country) && !country.equals(SELLER_COUNTRY)) {
            return new TaxDecision(BigDecimal.ZERO, "reverse charge, VAT id " + customer.vatId());
        }
        return new TaxDecision(table.rate(country), "VAT " + country);
    }
}
