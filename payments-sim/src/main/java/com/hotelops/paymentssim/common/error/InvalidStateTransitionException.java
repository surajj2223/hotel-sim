package com.hotelops.paymentssim.common.error;

/**
 * Thrown when an operation cannot be performed in the payment's current state — 409 per
 * WAVE0_05 §2.5 (PSP-002 second capture, PSP-003 cancel-after-capture, etc.).
 */
public class InvalidStateTransitionException extends RuntimeException {

    private final String code;

    public InvalidStateTransitionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
