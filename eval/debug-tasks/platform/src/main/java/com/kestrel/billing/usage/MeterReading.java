package com.kestrel.billing.usage;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One record of the meter export.
 *
 * @param readingId the meter's id for the measurement; a correction carries the id of the reading it
 *        replaces
 * @param usageDay the day the usage belongs to, in the customer's calendar
 * @param recordedAt when the record was written, ISO-8601 with the writer's UTC offset as exported
 *        (meters write local time, the correction desk writes UTC); kept as text for the audit trail
 * @param correction whether this record corrects an earlier one
 */
public record MeterReading(String readingId, String customer, String resource, BigDecimal quantity, String unit,
        LocalDate usageDay, String recordedAt, boolean correction) {

    /** The moment the record was written. */
    public Instant recordedInstant() {
        return OffsetDateTime.parse(recordedAt).toInstant();
    }
}
