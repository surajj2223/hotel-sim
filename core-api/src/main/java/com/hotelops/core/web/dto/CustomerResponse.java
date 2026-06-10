package com.hotelops.core.web.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** API-001/002 response body — CustomerResponse (WAVE0_02_OPENAPI.yaml). */
public record CustomerResponse(
        UUID id,
        String shopperReference,
        String fullName,
        String email,
        String phone,
        OffsetDateTime createdAt
) {
}
