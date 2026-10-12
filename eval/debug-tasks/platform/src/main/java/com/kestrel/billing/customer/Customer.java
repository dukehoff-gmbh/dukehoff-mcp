package com.kestrel.billing.customer;

/**
 * A customer as billing sees them.
 *
 * @param country ISO country code of the billing address
 * @param currency invoice currency (EUR, CHF or GBP)
 * @param vatId VAT identification number, empty for consumers
 * @param yearsActive full years since the first contract
 */
public record Customer(String id, String name, String country, String currency, String vatId, int yearsActive,
        boolean student) {

    public boolean isBusiness() {
        return !vatId.isEmpty();
    }
}
