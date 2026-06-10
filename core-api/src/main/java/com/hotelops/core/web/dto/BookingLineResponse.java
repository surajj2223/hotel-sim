package com.hotelops.core.web.dto;

import com.hotelops.core.common.enums.BookingLineStatus;
import com.hotelops.core.common.enums.Vertical;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * API-006/007 line item — BookingLineResponse (WAVE0_02_OPENAPI.yaml).
 * unitPrice / lineAmount are MINOR UNITS; lineAmount == unitPrice * quantity.
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
        String currency
) {
}
