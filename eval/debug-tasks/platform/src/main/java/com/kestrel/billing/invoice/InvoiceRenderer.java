package com.kestrel.billing.invoice;

/**
 * The plain-text statement sent with an invoice.
 */
public final class InvoiceRenderer {

    public String render(Invoice invoice) {
        StringBuilder sb = new StringBuilder();
        sb.append("Invoice ").append(invoice.customer()).append(' ').append(invoice.period())
                .append(" (tariff ").append(invoice.tariff()).append(")\n");
        for (InvoiceLine line : invoice.lines()) {
            sb.append(String.format("  %-8s %-40s %12s EUR%n", line.kind(), line.text(), line.amount()));
        }
        sb.append(String.format("  %-49s %12s EUR%n", "net", invoice.net()));
        if (!invoice.currency().equals("EUR")) {
            sb.append(String.format("  %-49s %12s%n", "rate " + invoice.currency() + "/EUR", invoice.rate()));
        }
        sb.append(String.format("  %-49s %12s %s%n", "total", invoice.total().toPlainString(), invoice.currency()));
        return sb.toString();
    }
}
