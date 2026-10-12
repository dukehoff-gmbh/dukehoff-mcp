package com.kestrel.billing.config;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The configuration of one tariff: the base {@code tariffs.properties}, overlaid by
 * {@code tariffs-<tariff>.properties} if the tariff has one.
 */
public final class TariffConfig {

    private static final Map<String, TariffConfig> CACHE = new ConcurrentHashMap<>();

    private final String tariff;
    private final Properties properties = new Properties();

    private TariffConfig(String tariff) {
        this.tariff = tariff;
    }

    public static TariffConfig load(String tariff) {
        return CACHE.computeIfAbsent(tariff, t -> {
            TariffConfig config = new TariffConfig(t);
            config.read("tariffs.properties", true);
            config.read("tariffs-" + t.toLowerCase(Locale.ROOT) + ".properties", false);
            return config;
        });
    }

    private void read(String resource, boolean required) {
        try (InputStream in = TariffConfig.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                if (required) {
                    throw new IllegalStateException("missing " + resource);
                }
                return;
            }
            Properties layer = new Properties();
            layer.load(in);
            properties.putAll(layer);
        } catch (IOException ex) {
            throw new IllegalStateException("cannot read " + resource, ex);
        }
    }

    public String tariff() {
        return tariff;
    }

    public boolean has(String key) {
        return properties.containsKey(key);
    }

    public String get(String key) {
        String value = properties.getProperty(key);
        if (value == null) {
            throw new IllegalStateException("tariff " + tariff + ": no " + key);
        }
        return value.strip();
    }

    public BigDecimal decimal(String key) {
        return new BigDecimal(get(key));
    }

    public int integer(String key) {
        return Integer.parseInt(get(key));
    }

    /** The rules of this tariff as configured ({@code name:priority,...}), in configuration order. */
    public List<RuleSpec> rules() {
        List<RuleSpec> specs = new ArrayList<>();
        for (String entry : get("tariff." + tariff + ".rules").split(",")) {
            String[] parts = entry.strip().split(":");
            if (parts.length != 2) {
                throw new IllegalStateException("tariff " + tariff + ": rule entry '" + entry + "' is not name:priority");
            }
            specs.add(new RuleSpec(parts[0].strip(), parts[1].strip()));
        }
        return specs;
    }
}
