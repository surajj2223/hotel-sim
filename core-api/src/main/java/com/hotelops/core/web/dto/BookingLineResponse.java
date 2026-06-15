package com.hotelops.core.web.dto;

import com.hotelops.core.common.enums.BookingLineStatus;
import com.hotelops.core.common.enums.Vertical;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * API-006/007 line item — BookingLineResponse (WAVE0_02_OPENAPI.yaml).
 * unitPrice / lineAmount are MINOR UNITS. unitPrice is the per-unit rate; lineAmount is the
 * total line debt owned by the vertical strategy — unitPrice × quantity for verticals with
 * no duration dimension, and unitPrice × quantity × nights for ROOM
 * (see contracts/KNOWN_LIMITATION_ROOM_PRICING.md).
 *
 * WHK-016 (Slice S2, DRAFT amendment) — {@code revenuePosted} is a DERIVED, read-only
 * figure: the net revenue posted to the ledger for this line (sum of REVENUE less
 * REFUND_REVERSAL, minor units). It makes scoped allocation HTTP-visible — a scoped £200
 * spa capture shows £200 on the spa line and £0 on the room line. Computed at assembly from
 * {@code ledger_posting}; never persisted.
 */
public record BookingLineResponse(
        UUID id,
        UUID productId,
        Vertical vertical,
        BookingLineStatus status,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        int quantity,
        long unitPrice,
        long lineAmount,
        String currency,
        long revenuePosted
) {
}
