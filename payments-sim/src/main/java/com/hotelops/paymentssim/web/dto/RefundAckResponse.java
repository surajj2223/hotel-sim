package com.hotelops.paymentssim.web.dto;

/**
 * PSP-004 / WAVE0_05 §2.4 response (202). {@code pspReference} is a *distinct* fresh
 * PSP-minted reference (PSP-010); {@code originalReference} links back to the parent.
 * Wire-only "PENDING_REFUND" status.
 */
public record RefundAckResponse(
        String pspReference,
        String originalReference,
        String refundMerchantReference,
        long amount,
        String currency,
        String status
) {
    public static final String PENDING_REFUND = "PENDING_REFUND";
}
