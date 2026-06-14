package com.hotelops.core.web.dto;

import com.hotelops.core.common.enums.CaptureMode;
import com.hotelops.core.payment.LineCoverage;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * API-008 request body — PaymentLinkCreateRequest (WAVE0_02_OPENAPI.yaml).
 *
 * {@code merchantReference} is NOT accepted here — the server mints it (SCH-031, API-008).
 * {@code captureMode} defaults from the booking's vertical strategy when omitted
 * (Rooms MANUAL, F&B IMMEDIATE).
 *
 * WHK-016 (Slice S2, DRAFT amendment) — {@code lineCoverage} is OPTIONAL scoped coverage:
 * each entry settles one booking line for a given amount. Omitted/empty → folio-wide
 * (today's behaviour, WHK-012 fill-by-line-order). When present, amounts must sum to
 * {@code amount} (else 400, enforced in {@code PaymentService.persistCoverage}). Core-side
 * only — never forwarded to {@code payments-sim}.
 */
public record PaymentLinkCreateRequest(
        @NotNull @Positive Long amount,
        String currency,
        CaptureMode captureMode,
        List<LineCoverage> lineCoverage
) {
}
