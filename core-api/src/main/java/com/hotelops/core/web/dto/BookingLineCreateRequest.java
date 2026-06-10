package com.hotelops.core.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * API-006 request body — BookingLineCreateRequest (WAVE0_02_OPENAPI.yaml).
 * The caller does NOT send price; the server re-snapshots it at write time (INV-003).
 */
public record BookingLineCreateRequest(
        @NotNull UUID productId,
        @NotNull OffsetDateTime startsAt,
        @NotNull OffsetDateTime endsAt,
        @NotNull @Min(1) Integer quantity
) {
}
