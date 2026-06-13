package com.hotelops.core.payment.psp.dto;

/** Outbound PSP-004 request body (WAVE0_05 §2.4). */
public record PspRefundRequest(
        long amount,
        String refundMerchantReference,
        String reason
) {
}
