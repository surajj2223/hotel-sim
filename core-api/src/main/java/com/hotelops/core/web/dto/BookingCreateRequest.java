package com.hotelops.core.web.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * API-005 request body — BookingCreateRequest (WAVE0_02_OPENAPI.yaml).
 * currency defaults to GBP when omitted (the service applies the default).
 */
public record BookingCreateRequest(
        @NotNull UUID customerId,
        String currency
) {
}
