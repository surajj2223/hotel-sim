package com.hotelops.core.web.dto;

import com.hotelops.core.common.enums.BookingStatus;

import java.util.List;
import java.util.UUID;

/**
 * API-005/006/007 response body — FolioResponse (WAVE0_02_OPENAPI.yaml).
 *
 * totalAmount / amountPaid / amountRefunded / balance are server-derived (INV-004) and
 * read-only over the API; balance == totalAmount - amountPaid + amountRefunded (SCH-021).
 * All amounts are MINOR UNITS.
 */
public record FolioResponse(
        UUID id,
        UUID customerId,
        BookingStatus status,
        String currency,
        long totalAmount,
        long amountPaid,
        long amountRefunded,
        long balance,
        List<BookingLineResponse> lines
) {
}
