package com.hotelops.core.payment;

import java.util.UUID;

/**
 * WHK-016 — a single coverage instruction: "this payment covers booking line
 * {@code bookingLineId} for {@code amount} (minor units)". A list of these, when supplied to
 * {@link PaymentService#createPaymentLink}, persists the scoped {@link PaymentLine} rows.
 * The amounts must sum exactly to the payment's requested amount (rejected 400 otherwise).
 */
public record LineCoverage(UUID bookingLineId, long amount) {
}
