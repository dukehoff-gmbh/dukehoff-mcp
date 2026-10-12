package com.kestrel.billing;

/**
 * A customer as billing sees them.
 *
 * @param tariff the tariff name as configured, e.g. STANDARD
 * @param yearsActive full years since the contract started
 */
public record Customer(String id, String tariff, int yearsActive, boolean student) {
}
