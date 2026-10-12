package com.kestrel.billing.usage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves corrections and redeliveries: of all records with the same reading id, the one written
 * last counts. The export may deliver a record twice and lists corrections after the readings of
 * their day, not in the order they were written.
 */
public final class CorrectionMerger {

    private static final Comparator<MeterReading> WRITTEN = Comparator.comparing(MeterReading::recordedInstant);

    public List<MeterReading> merge(List<MeterReading> readings) {
        Map<String, MeterReading> latest = new LinkedHashMap<>();
        for (MeterReading r : readings) {
            latest.merge(r.readingId(), r, (a, b) -> WRITTEN.compare(b, a) > 0 ? b : a);
        }
        return new ArrayList<>(latest.values());
    }
}
