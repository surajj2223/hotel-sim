package com.hotelops.paymentssim.web.dto;

/**
 * PSP-002 / WAVE0_05 §2.2 response (202). {@code status} is the wire-only ack
 * "PENDING_CAPTURE" — NOT an ENM-005 PaymentStatus value, never persisted by core-api.
 */
public record CaptureAckResponse(
        String pspReference,
        String merchantReference,
        long amount,
        String status
) {
    public static final String PENDING_CAPTURE = "PENDING_CAPTURE";
}
