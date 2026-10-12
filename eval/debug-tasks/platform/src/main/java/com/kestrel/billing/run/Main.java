package com.kestrel.billing.run;

import com.kestrel.billing.invoice.Invoice;
import com.kestrel.billing.invoice.InvoiceRenderer;
import com.kestrel.billing.time.BillingPeriod;
import java.nio.file.Path;

/**
 * Runs a billing period: {@code Main <data dir> <yyyy-mm> [--totals]}. Prints the statements, or with
 * {@code --totals} one {@code customer;currency;total} line per invoice.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: Main <data dir> <yyyy-mm> [--totals]");
            System.exit(2);
        }
        boolean totals = args.length > 2 && args[2].equals("--totals");
        InvoiceRenderer renderer = new InvoiceRenderer();
        for (Invoice invoice : new BillingRun(DataSet.load(Path.of(args[0]))).run(BillingPeriod.parse(args[1]))) {
            if (totals) {
                System.out.println(invoice.customer() + ";" + invoice.currency() + ";" + invoice.total().toPlainString());
            } else {
                System.out.println(renderer.render(invoice));
            }
        }
    }
}
