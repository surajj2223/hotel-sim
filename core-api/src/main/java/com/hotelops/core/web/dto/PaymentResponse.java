package com.hotelops.core.web.dto;

import com.hotelops.core.common.enums.CaptureMode;
import com.hotelops.core.common.enums.PaymentStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * API-008/009/010/011 response body — PaymentResponse (WAVE0_02_OPENAPI.yaml).
 *
 * Reference taxonomy: {@code shopperReference} from customer; {@code merchantReference}
 * server-minted (SCH-031); {@code pspReference} stamped on AUTHORISATION (null until then);
 * {@code paymentLinkId} stamped on link creation by payments-sim (null until Feature 2).
 * Amounts are MINOR UNITS.
 */
public record PaymentResponse(
        UUID id,
        UUID bookingId,
        PaymentStatus status,
        CaptureMode captureMode,
        String currency,
        long amountRequested,
        long amountAuthorised,
        long amountCaptured,
        long amountRefunded,
        String shopperReference,
        String merchantReference,
        String pspReference,
        String paymentLinkId,
        OffsetDateTime authExpiresAt,
        OffsetDateTime createdAt,
        List<RefundResponse> refunds
) {
}
