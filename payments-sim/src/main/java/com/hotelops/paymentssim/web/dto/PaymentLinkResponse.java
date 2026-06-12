package com.hotelops.paymentssim.web.dto;

import com.hotelops.paymentssim.domain.PspPaymentStatus;

/** PSP-001 / WAVE0_05 §2.1 response body (201). */
public record PaymentLinkResponse(
        String paymentLinkId,
        String merchantReference,
        String shopperReference,
        long amount,
        String currency,
        PspPaymentStatus status,
        String hostedUrl
) {}
