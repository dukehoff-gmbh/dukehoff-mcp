package com.kestrel.billing.rules;

import com.kestrel.billing.BillingContext;
import com.kestrel.billing.Money;
import com.kestrel.billing.config.RuleSpec;
import com.kestrel.billing.config.TariffConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The rules of a tariff in the order they apply: ascending priority.
 */
public final class RuleChain {

    private final List<RuleSpec> specs;
    private final List<Rule> rules;

    private RuleChain(List<RuleSpec> specs, List<Rule> rules) {
        this.specs = specs;
        this.rules = rules;
    }

    public static RuleChain of(TariffConfig config, RuleFactory factory) {
        List<RuleSpec> specs = new ArrayList<>(config.rules());
        Collections.sort(specs);
        List<Rule> rules = new ArrayList<>();
        for (RuleSpec spec : specs) {
            rules.add(factory.create(spec.name()));
        }
        return new RuleChain(List.copyOf(specs), List.copyOf(rules));
    }

    public Money apply(Money amount, BillingContext context) {
        Money current = amount;
        for (Rule rule : rules) {
            current = rule.apply(current, context);
        }
        return current;
    }

    /** The rule names in the order they apply. */
    public List<String> names() {
        return specs.stream().map(RuleSpec::name).toList();
    }
}
