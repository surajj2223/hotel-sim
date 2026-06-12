package com.hotelops.paymentssim.web.dto;

/** PSP-003 / WAVE0_05 §2.3 response (202). Wire-only "PENDING_CANCELLATION" status. */
public record CancellationAckResponse(
        String pspReference,
        String merchantReference,
        String status
) {
    public static final String PENDING_CANCELLATION = "PENDING_CANCELLATION";
}
