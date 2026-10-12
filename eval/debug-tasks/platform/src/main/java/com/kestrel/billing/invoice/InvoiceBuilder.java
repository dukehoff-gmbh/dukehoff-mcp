package com.kestrel.billing.invoice;

import com.kestrel.billing.BillingContext;
import com.kestrel.billing.Money;
import com.kestrel.billing.catalog.PriceList;
import com.kestrel.billing.catalog.TieredPrice;
import com.kestrel.billing.config.TariffConfig;
import com.kestrel.billing.credit.CreditLedger;
import com.kestrel.billing.customer.ContractHistory;
import com.kestrel.billing.customer.Customer;
import com.kestrel.billing.customer.Proration;
import com.kestrel.billing.money.CurrencyRounding;
import com.kestrel.billing.money.ExchangeRates;
import com.kestrel.billing.rules.RuleChain;
import com.kestrel.billing.rules.RuleFactory;
import com.kestrel.billing.tax.TaxDecision;
import com.kestrel.billing.tax.TaxPolicy;
import com.kestrel.billing.time.BillingPeriod;
import com.kestrel.billing.usage.UsageSummary;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Prices one customer's period: usage at the tariff's tiered prices, then the tariff's rules (base
 * fee, discounts, limits), tax, credits, and the conversion into the invoice currency.
 */
public final class InvoiceBuilder {

    private final TaxPolicy taxes;
    private final ExchangeRates rates;
    private final CreditLedger credits;
    private final Proration proration = new Proration(TariffConfig::load);
    private final RuleFactory rules = new RuleFactory();
    private final CurrencyRounding rounding = new CurrencyRounding();

    public InvoiceBuilder(TaxPolicy taxes, ExchangeRates rates, CreditLedger credits) {
        this.taxes = taxes;
        this.rates = rates;
        this.credits = credits;
    }

    public Invoice build(Customer customer, ContractHistory history, UsageSummary usage, BillingPeriod period) {
        String tariff = history.tariffAtEnd(period);
        TariffConfig config = TariffConfig.load(tariff);
        PriceList prices = PriceList.of(config);
        List<InvoiceLine> lines = new ArrayList<>();

        Money usageCharges = Money.ZERO;
        for (Map.Entry<String, BigDecimal> e : usage.quantities().entrySet()) {
            TieredPrice price = prices.of(e.getKey());
            for (TieredPrice.Step step : price.steps(e.getValue())) {
                lines.add(new InvoiceLine("usage", e.getKey() + " " + step.quantity().stripTrailingZeros().toPlainString()
                        + " x " + step.tier().unitPrice(), step.amount()));
                usageCharges = usageCharges.plus(step.amount());
            }
        }

        Money baseFee = proration.baseFee(history, period);
        BillingContext context = new BillingContext(customer, period, usage, config, baseFee);
        RuleChain.Outcome outcome = RuleChain.of(config, rules).apply(usageCharges, context);
        for (RuleChain.Adjustment a : outcome.adjustments()) {
            lines.add(new InvoiceLine("rule", a.label(), a.change()));
        }
        Money net = outcome.amount();

        TaxDecision decision = taxes.decide(customer);
        Money tax = net.percent(decision.rate());
        lines.add(new InvoiceLine("tax", decision.note() + " " + decision.rate().stripTrailingZeros().toPlainString() + "%", tax));

        Money used = Money.ZERO;
        for (CreditLedger.Use use : credits.use(customer.id(), net.plus(tax), period.lastDay())) {
            lines.add(new InvoiceLine("credit", use.creditId(), Money.ZERO.minus(use.amount())));
            used = used.plus(use.amount());
        }

        Money due = net.plus(tax).minus(used);
        BigDecimal rate = rates.rate(customer.currency(), period.lastDay());
        BigDecimal total = rounding.convert(due.amount(), rate, customer.currency());
        return new Invoice(customer.id(), period, tariff, customer.currency(), usageCharges, net, tax, used, rate, total,
                List.copyOf(lines));
    }
}
