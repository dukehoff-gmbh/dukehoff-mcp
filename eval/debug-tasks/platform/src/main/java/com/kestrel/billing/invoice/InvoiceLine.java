package com.kestrel.billing.invoice;

import com.kestrel.billing.Money;

/**
 * One line of an invoice, in euros.
 *
 * @param kind usage, rule, tax or credit
 */
public record InvoiceLine(String kind, String text, Money amount) {
}
