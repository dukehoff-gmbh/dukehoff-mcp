package com.kestrel.billing.config;

/**
 * A rule as a tariff lists it. Specs order by priority: lower runs first.
 *
 * @param name the rule's name, e.g. volume
 * @param priority the priority as configured
 */
public record RuleSpec(String name, String priority) implements Comparable<RuleSpec> {

    @Override
    public int compareTo(RuleSpec other) {
        return Integer.compare(Integer.parseInt(priority), Integer.parseInt(other.priority));
    }
}
