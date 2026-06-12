package com.hotelops.paymentssim.common.error;

/**
 * Thrown when a requested amount exceeds the allowed bound (capture &gt; authorised,
 * refund &gt; capturable) — 422 per WAVE0_05 §2.5 (PSP-002 / PSP-004).
 */
public class AmountExceededException extends RuntimeException {

    private final String code;

    public AmountExceededException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
