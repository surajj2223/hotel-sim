package com.hotelops.core.web.dto;

import com.hotelops.core.common.enums.PspEventCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

/**
 * API-013 / WHK-002 — inbound PSP event envelope (WAVE0_02_OPENAPI.yaml,
 * WAVE0_03_WEBHOOK_PSP_CONTRACT.md §3).
 *
 * {@code amount} meaning varies by {@code eventCode} (see WAVE0_03 §3).
 * Event-specific nullable fields:
 *   AUTHORISATION → authExpiresAt
 *   REFUND / REFUND_FAILED → originalReference, refundMerchantReference (REFUND_FAILED also reason)
 *   *_FAILED → reason
 */
public record PspWebhookEvent(
        @NotBlank String eventId,
        @NotNull PspEventCode eventCode,
        @NotBlank String idempotencyKey,
        @NotBlank String merchantReference,
        String pspReference,
        @NotNull Long amount,
        @NotBlank String currency,
        @NotNull OffsetDateTime occurredAt,
        @NotNull Boolean success,
        OffsetDateTime authExpiresAt,
        String originalReference,
        String refundMerchantReference,
        String reason
) {
}
