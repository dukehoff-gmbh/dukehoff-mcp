package com.kestrel.billing.catalog;

import com.kestrel.billing.config.TariffConfig;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A tariff's prices per resource ({@code price.<resource>} in the tariff configuration).
 */
public final class PriceList {

    public static final List<String> RESOURCES = List.of("api", "compute", "storage", "traffic");

    private final Map<String, TieredPrice> prices = new TreeMap<>();

    private PriceList() {
    }

    public static PriceList of(TariffConfig config) {
        PriceList list = new PriceList();
        for (String resource : RESOURCES) {
            list.prices.put(resource, TieredPrice.parse(config.get("price." + resource)));
        }
        return list;
    }

    public TieredPrice of(String resource) {
        TieredPrice price = prices.get(resource);
        if (price == null) {
            throw new IllegalArgumentException("no price for " + resource);
        }
        return price;
    }
}
