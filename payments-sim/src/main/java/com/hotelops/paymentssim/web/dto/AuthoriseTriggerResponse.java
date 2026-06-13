package com.hotelops.paymentssim.web.dto;

import com.hotelops.paymentssim.domain.PspPaymentStatus;

/** WAVE0_05 §5.1 — response body after the AUTHORISE trigger fires. */
public record AuthoriseTriggerResponse(
        String paymentLinkId,
        String pspReference,
        long amountAuthorised,
        PspPaymentStatus status) {
}
