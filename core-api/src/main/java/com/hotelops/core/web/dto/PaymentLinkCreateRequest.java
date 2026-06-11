package com.hotelops.core.web.dto;

import com.hotelops.core.common.enums.CaptureMode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * API-008 request body — PaymentLinkCreateRequest (WAVE0_02_OPENAPI.yaml).
 *
 * {@code merchantReference} is NOT accepted here — the server mints it (SCH-031, API-008).
 * {@code captureMode} defaults from the booking's vertical strategy when omitted
 * (Rooms MANUAL, F&B IMMEDIATE).
 */
public record PaymentLinkCreateRequest(
        @NotNull @Positive Long amount,
        String currency,
        CaptureMode captureMode
) {
}
