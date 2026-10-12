package com.kestrel.billing.credit;

import com.kestrel.billing.Money;
import java.time.LocalDate;

/**
 * A prepaid credit (voucher, goodwill, prepayment) in euros, usable until {@code expires} inclusive.
 */
public record Credit(String id, String customer, Money amount, LocalDate expires) {
}
