package com.hotelops.core.web.dto;

import com.hotelops.core.common.enums.RefundStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * API-012 response body — RefundResponse (WAVE0_02_OPENAPI.yaml).
 *
 * {@code originalReference} is the parent payment's pspReference — the PSP parent/child
 * chain (SCH-040). {@code pspReference} is null until the REFUND webhook lands (WHK-009).
 */
public record RefundResponse(
        UUID id,
        UUID paymentId,
        long amount,
        String currency,
        RefundStatus status,
        String merchantReference,
        String pspReference,
        String originalReference,
        String reason,
        OffsetDateTime createdAt
) {
}
