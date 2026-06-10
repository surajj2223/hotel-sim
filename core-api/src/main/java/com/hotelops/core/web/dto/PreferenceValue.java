package com.hotelops.core.web.dto;

import jakarta.validation.constraints.NotBlank;

/** API-003 request body — PreferenceValue (WAVE0_02_OPENAPI.yaml). */
public record PreferenceValue(
        @NotBlank String value
) {
}
