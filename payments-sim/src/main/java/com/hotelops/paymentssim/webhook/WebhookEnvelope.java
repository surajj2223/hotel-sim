package com.hotelops.paymentssim.webhook;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hotelops.paymentssim.domain.PspEventCode;
import java.time.OffsetDateTime;

/**
 * WAVE0_03 §3 webhook envelope. Common fields are always present; event-specific
 * fields are populated per the §3 table and serialized with {@code NON_NULL} so
 * each event only carries what {@code WAVE0_03 §3} requires (per WHK-002).
 *
 * <p>{@code idempotencyKey} format per WHK-003 / PSP-011:
 * {@code pspReference + ":" + eventCode + ":" + seq}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WebhookEnvelope(
        String eventId,
        PspEventCode eventCode,
        String idempotencyKey,
        String merchantReference,
        String pspReference,
        long amount,
        String currency,
        OffsetDateTime occurredAt,
        boolean success,
        // event-specific (nullable; only present where §3 demands)
        OffsetDateTime authExpiresAt,
        String reason,
        String originalReference,
        String refundMerchantReference
) {
}
