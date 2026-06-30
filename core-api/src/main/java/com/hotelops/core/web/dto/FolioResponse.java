package com.hotelops.core.web.dto;

import com.hotelops.core.common.enums.BookingStatus;

import java.util.List;
import java.util.UUID;

/**
 * API-005/006/007 response body — FolioResponse (WAVE0_02_OPENAPI.yaml).
 *
 * totalAmount / amountPaid / amountRefunded / customerOwes / netRevenue are server-derived
 * (INV-004) and read-only over the API. RX-003 split the former single {@code balance} into
 * {@code customerOwes == max(0, totalAmount - amountPaid)} (settlement; "paid" == owes 0) and
 * {@code netRevenue == amountPaid - amountRefunded} (finance read).
 * amountAuthorised (RX-004) is the live "secured" hold — sum of payment.amountAuthorised over
 * AUTHORISED-status payments only, DERIVED ON READ (a captured auth is spent and excluded); not
 * stored on the booking. Visible only, no enforcement. All amounts are MINOR UNITS.
 */
public record FolioResponse(
        UUID id,
        UUID customerId,
        BookingStatus status,
        String currency,
        long totalAmount,
        long amountPaid,
        long amountRefunded,
        long amountAuthorised,
        long customerOwes,
        long netRevenue,
        List<BookingLineResponse> lines
) {
}
