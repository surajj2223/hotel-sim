package com.hotelops.paymentssim.common.error;

/**
 * Thrown when a UNIQUE reference anchor ({@code merchantReference} on
 * {@code psp_payment}, {@code refundMerchantReference} on {@code psp_refund}) is
 * resubmitted — 409 per WAVE0_05 §2.5 (PSP-001 / PSP-004).
 */
public class DuplicateAnchorException extends RuntimeException {

    private final String code;

    public DuplicateAnchorException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
