package com.kestrel.billing;

import com.kestrel.billing.config.TariffConfig;
import com.kestrel.billing.rules.RuleChain;
import com.kestrel.billing.rules.RuleFactory;
import com.kestrel.billing.usage.UsageParser;
import com.kestrel.billing.usage.UsageRecord;
import com.kestrel.billing.usage.Units;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Prices a customer's monthly usage: converts the metered quantities into billing units, prices them
 * with the tariff's price list, applies the tariff's rules and adds tax.
 */
public final class InvoiceService {

    private final UsageParser parser = new UsageParser();
    private final Units units = new Units();
    private final RuleFactory rules = new RuleFactory();

    public Invoice invoice(Customer customer, List<String> usageExport) {
        TariffConfig config = TariffConfig.load(customer.tariff());
        Map<String, BigDecimal> usage = new TreeMap<>();
        for (UsageRecord r : parser.parse(usageExport)) {
            if (r.customer().equals(customer.id())) {
                usage.merge(r.resource(), units.toBillingUnit(r), BigDecimal::add);
            }
        }
        Money gross = Money.ZERO;
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : usage.entrySet()) {
            Money price = Money.of(config.decimal("price." + e.getKey())).times(BigDecimal.ONE);
            Money line = Money.of(e.getValue().multiply(price.amount()));
            gross = gross.plus(line);
            lines.add(e.getKey() + ": " + e.getValue().stripTrailingZeros().toPlainString() + " x " + price + " = " + line);
        }
        BillingContext context = new BillingContext(customer, usage, config);
        Money net = RuleChain.of(config, rules).apply(gross, context);
        Money tax = net.percent(config.decimal("tax.rate"));
        return new Invoice(customer.id(), gross, net, tax, net.plus(tax), List.copyOf(lines));
    }
}
