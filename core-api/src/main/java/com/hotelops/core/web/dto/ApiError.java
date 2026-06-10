package com.hotelops.core.web.dto;

/** Standard error envelope for 4xx/5xx — ApiError (WAVE0_02_OPENAPI.yaml). */
public record ApiError(
        String code,
        String message
) {
}
