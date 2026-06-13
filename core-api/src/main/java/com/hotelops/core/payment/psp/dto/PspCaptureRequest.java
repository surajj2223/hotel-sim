package com.hotelops.core.payment.psp.dto;

/** Outbound PSP-002 request body (WAVE0_05 §2.2). {@code null} amount = full capture. */
public record PspCaptureRequest(Long amount) {
}
