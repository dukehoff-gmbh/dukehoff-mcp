package com.kestrel.billing.customer;

import java.time.LocalDate;

/**
 * One entry of a customer's contract history: from {@code from} on, the customer is on {@code tariff}
 * until the next entry.
 */
public record Contract(String customer, String tariff, LocalDate from) {
}
