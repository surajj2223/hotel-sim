package com.hotelops.paymentssim.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** PSP-004 / WAVE0_05 §2.4 request body. */
public record RefundRequest(
        @NotNull @Positive Long amount,
        @NotBlank String refundMerchantReference,
        String reason
) {}
