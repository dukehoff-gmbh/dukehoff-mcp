package com.kestrel.billing.run;

import com.kestrel.billing.credit.CreditLedger;
import com.kestrel.billing.customer.Contract;
import com.kestrel.billing.customer.ContractHistory;
import com.kestrel.billing.customer.Customer;
import com.kestrel.billing.money.ExchangeRates;
import com.kestrel.billing.tax.TaxTable;
import com.kestrel.billing.usage.MeterReading;
import com.kestrel.billing.usage.ReadingParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything a billing run reads, from one directory: customers.csv, contracts.csv, usage.csv,
 * rates.csv, tax-rates.csv, credits.csv.
 */
public final class DataSet {

    private final Map<String, Customer> customers = new LinkedHashMap<>();
    private final Map<String, ContractHistory> contracts = new HashMap<>();
    private List<MeterReading> usage;
    private ExchangeRates rates;
    private TaxTable taxes;
    private CreditLedger credits;

    private DataSet() {
    }

    public static DataSet load(Path dir) {
        DataSet d = new DataSet();
        for (Csv.Row row : Csv.rows(read(dir, "customers.csv"), 7)) {
            Customer c = new Customer(row.get(0), row.get(1), row.get(2), row.get(3), row.get(4),
                    Integer.parseInt(row.get(5)), Boolean.parseBoolean(row.get(6)));
            d.customers.put(c.id(), c);
        }
        Map<String, List<Contract>> byCustomer = new HashMap<>();
        for (Csv.Row row : Csv.rows(read(dir, "contracts.csv"), 3)) {
            byCustomer.computeIfAbsent(row.get(0), k -> new ArrayList<>())
                    .add(new Contract(row.get(0), row.get(1), LocalDate.parse(row.get(2))));
        }
        byCustomer.forEach((id, list) -> d.contracts.put(id, new ContractHistory(id, list)));
        d.usage = new ReadingParser().parse(read(dir, "usage.csv"));
        d.rates = ExchangeRates.parse(read(dir, "rates.csv"));
        d.taxes = TaxTable.parse(read(dir, "tax-rates.csv"));
        d.credits = CreditLedger.parse(read(dir, "credits.csv"));
        return d;
    }

    private static List<String> read(Path dir, String name) {
        try {
            return Files.readAllLines(dir.resolve(name));
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot read " + name + " in " + dir, ex);
        }
    }

    public List<Customer> customers() {
        return List.copyOf(customers.values());
    }

    public ContractHistory contracts(String customer) {
        ContractHistory h = contracts.get(customer);
        if (h == null) {
            throw new IllegalStateException("no contracts for " + customer);
        }
        return h;
    }

    public List<MeterReading> usage() {
        return usage;
    }

    public ExchangeRates rates() {
        return rates;
    }

    public TaxTable taxes() {
        return taxes;
    }

    public CreditLedger credits() {
        return credits;
    }
}
