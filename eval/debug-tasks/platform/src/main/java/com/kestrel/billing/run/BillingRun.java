package com.kestrel.billing.run;

import com.kestrel.billing.customer.Customer;
import com.kestrel.billing.invoice.Invoice;
import com.kestrel.billing.invoice.InvoiceBuilder;
import com.kestrel.billing.tax.TaxPolicy;
import com.kestrel.billing.time.BillingPeriod;
import com.kestrel.billing.usage.UsagePipeline;
import com.kestrel.billing.usage.UsageSummary;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The monthly run: usage for all customers, then one invoice per customer (also without usage: the
 * base fee is due anyway), in customer order.
 */
public final class BillingRun {

    private final DataSet data;

    public BillingRun(DataSet data) {
        this.data = data;
    }

    public List<Invoice> run(BillingPeriod period) {
        Map<String, UsageSummary> usage = new UsagePipeline().run(data.usage(), period);
        InvoiceBuilder builder = new InvoiceBuilder(new TaxPolicy(data.taxes()), data.rates(), data.credits());
        List<Customer> customers = new ArrayList<>(data.customers());
        customers.sort(Comparator.comparing(Customer::id));
        List<Invoice> invoices = new ArrayList<>();
        for (Customer c : customers) {
            invoices.add(builder.build(c, data.contracts(c.id()), usage.getOrDefault(c.id(), UsageSummary.empty(c.id())),
                    period));
        }
        return invoices;
    }
}
