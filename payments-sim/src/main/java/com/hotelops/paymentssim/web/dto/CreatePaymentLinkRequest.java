package com.hotelops.paymentssim.web.dto;

import com.hotelops.paymentssim.domain.CaptureMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** PSP-001 / WAVE0_05 §2.1 request body. */
public record CreatePaymentLinkRequest(
        @NotBlank String merchantReference,
        @NotBlank String shopperReference,
        @NotNull @Positive Long amount,
        @NotBlank @Size(min = 3, max = 3) String currency,
        @NotNull CaptureMode captureMode,
        // callbackUrl is optional per §2.1; when omitted, payments-sim uses
        // CORE_API_WEBHOOK_URL (PSP-016). 1B persists whatever is sent; 1C reads it.
        String callbackUrl
) {}
