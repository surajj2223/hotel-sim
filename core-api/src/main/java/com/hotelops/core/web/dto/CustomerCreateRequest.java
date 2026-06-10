package com.hotelops.core.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * API-001 request body — CustomerCreateRequest (WAVE0_02_OPENAPI.yaml).
 * shopperReference is NOT accepted here; the server mints it (INV-001).
 */
public record CustomerCreateRequest(
        @NotBlank String fullName,
        String email,
        String phone
) {
}
