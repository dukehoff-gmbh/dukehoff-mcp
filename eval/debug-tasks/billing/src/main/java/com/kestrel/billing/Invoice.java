package com.kestrel.billing;

import java.util.List;

/**
 * An invoice: the net amount after the tariff's rules, the tax on it, and the total.
 *
 * @param lines one line per resource used, for the customer's statement
 */
public record Invoice(String customer, Money gross, Money net, Money tax, Money total, List<String> lines) {
}
