package com.hotelops.core.web.dto;

/**
 * API-010 request body — CaptureRequest (WAVE0_02_OPENAPI.yaml).
 *
 * {@code amount} nullable: omitted = full capture (the service uses {@code amountAuthorised}).
 * Must be &lt;= amountAuthorised (SCH-032) and single-capture only (INV-005); both are
 * enforced server-side in {@link com.hotelops.core.payment.PaymentService#requestCapture}.
 */
public record CaptureRequest(
        Long amount
) {
}
