package com.hotelops.core.web.dto;

/**
 * Body of a 409 from INV-003 write-time revalidation — StateConflict (WAVE0_02_OPENAPI.yaml).
 *
 * allOf(ApiError) + currentState: the same {@code code}/{@code message} envelope plus a
 * free-form {@code currentState} object carrying the current truth (e.g. available
 * capacity) so the caller can re-read and retry.
 */
public record StateConflict(
        String code,
        String message,
        Object currentState
) {
}
