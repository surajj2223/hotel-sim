package com.hotelops.core.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * API-012 request body — RefundRequest (WAVE0_02_OPENAPI.yaml).
 *
 * Must be &lt;= (captured − alreadyRefunded) (SCH-033); enforced server-side.
 */
public record RefundRequest(
        @NotNull @Positive Long amount,
        String reason
) {
}
