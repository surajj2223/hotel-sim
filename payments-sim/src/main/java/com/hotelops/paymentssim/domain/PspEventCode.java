package com.hotelops.paymentssim.domain;

/** Mirrors ENM-007 PspEventCode. */
public enum PspEventCode {
    AUTHORISATION,
    CAPTURE,
    CAPTURE_FAILED,
    CANCELLATION,
    REFUND,
    REFUND_FAILED,
    AUTH_EXPIRY
}
