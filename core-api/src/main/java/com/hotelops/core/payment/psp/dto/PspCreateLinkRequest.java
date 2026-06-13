package com.hotelops.core.payment.psp.dto;

import com.hotelops.core.common.enums.CaptureMode;

/**
 * Outbound PSP-001 request body (WAVE0_05 §2.1). {@code callbackUrl} is optional — when
 * null, {@code payments-sim} falls back to its configured {@code CORE_API_WEBHOOK_URL}
 * (PSP-016), which in the POC compose is {@code core-api}'s own {@code /webhooks/psp}.
 */
public record PspCreateLinkRequest(
        String merchantReference,
        String shopperReference,
        long amount,
        String currency,
        CaptureMode captureMode,
        String callbackUrl
) {
}
