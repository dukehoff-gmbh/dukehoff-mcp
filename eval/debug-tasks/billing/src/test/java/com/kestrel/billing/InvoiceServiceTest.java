package com.kestrel.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class InvoiceServiceTest {

    private static final List<String> EXPORT = List.of(
            "# customer;resource;quantity;unit",
            "c-17;storage;180;GB",
            "c-17;traffic;2.5;TB",
            "c-17;compute;7200;min",
            "c-22;storage;40;GB",
            "c-22;compute;30;h",
            "c-31;storage;800;GB",
            "c-31;traffic;400;GB",
            "c-31;compute;250;h",
            "c-40;storage;60;GB",
            "c-40;traffic;900;MB");

    private final InvoiceService service = new InvoiceService();

    @Test
    void standardWithVolumeAndLoyalty() {
        Invoice invoice = service.invoice(new Customer("c-17", "STANDARD", 4, false), EXPORT);
        assertEquals(Money.of("283.40"), invoice.gross());
        assertEquals(Money.of("248.06"), invoice.net());
        assertEquals(Money.of("295.19"), invoice.total());
    }

    @Test
    void standardSmallCustomer() {
        Invoice invoice = service.invoice(new Customer("c-22", "STANDARD", 1, false), EXPORT);
        assertEquals(Money.of("55.60"), invoice.net());
        assertEquals(Money.of("66.16"), invoice.total());
    }

    @Test
    void studentGetsMinimum() {
        Invoice invoice = service.invoice(new Customer("c-22", "STUDENT", 0, true), EXPORT);
        assertEquals(Money.of("35.60"), invoice.gross());
        assertEquals(Money.of("38.92"), invoice.net());
        assertEquals(Money.of("46.31"), invoice.total());
    }

    @Test
    void businessNewCustomer() {
        Invoice invoice = service.invoice(new Customer("c-40", "BUSINESS", 1, false), EXPORT);
        assertEquals(Money.of("48.04"), invoice.gross());
        assertEquals(Money.of("57.83"), invoice.net());
        assertEquals(Money.of("68.82"), invoice.total());
    }

    @Test
    void businessLongTermCustomer() {
        Invoice invoice = service.invoice(new Customer("c-31", "BUSINESS", 5, false), EXPORT);
        assertEquals(Money.of("690.00"), invoice.gross());
        assertEquals(Money.of("578.50"), invoice.net());
        assertEquals(Money.of("688.42"), invoice.total());
    }

    @Test
    void statementLines() {
        Invoice invoice = service.invoice(new Customer("c-17", "STANDARD", 4, false), EXPORT);
        assertEquals(List.of("compute: 120 x 0.12 = 14.40", "storage: 180 x 0.80 = 144.00", "traffic: 2500 x 0.05 = 125.00"),
                invoice.lines());
    }
}
