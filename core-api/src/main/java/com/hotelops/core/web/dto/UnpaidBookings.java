package com.hotelops.core.web.dto;

import com.hotelops.core.common.enums.Vertical;

import java.util.List;
import java.util.UUID;

/**
 * API-017 listUnpaidBookings response body (WAVE0_02_OPENAPI.yaml, FROZEN).
 *
 * The full unpaid worklist: every booking where {@code total_amount > amount_paid} (the SQL form
 * of {@code customerOwes > 0}, RX-003), each with a per-line breakdown. All amounts MINOR UNITS.
 *
 * Per line (mirrors booking-level {@code customerOwes}, RX-003):
 *   - {@code lineRevenuePosted} = Σ REVENUE postings for the line (0 until a covering capture);
 *   - {@code lineOwes}          = lineAmount − lineRevenuePosted — CAPTURED ONLY; a held
 *     authorisation does NOT reduce owes;
 *   - {@code lineHeldAuth}      = Σ payment_line.amount whose parent payment is AUTHORISED —
 *     informational only, does not affect {@code lineOwes} (eventual-consistency caveat).
 *
 * Entities are never serialised — this DTO is assembled in {@code ReportingService}.
 */
public record UnpaidBookings(List<UnpaidBooking> bookings) {

    public record UnpaidBooking(
            UUID bookingId,
            String shopperReference,
            String customerName,
            String currency,
            long totalAmount,
            long amountPaid,
            long customerOwes,
            List<UnpaidBookingLine> lines
    ) {}

    public record UnpaidBookingLine(
            UUID lineId,
            Vertical vertical,
            long lineAmount,
            long lineRevenuePosted,
            long lineOwes,
            long lineHeldAuth
    ) {}
}
