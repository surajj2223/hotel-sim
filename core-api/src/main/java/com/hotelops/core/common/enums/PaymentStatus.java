package com.hotelops.core.common.enums;

/** ENM-005 — payment state machine (9 states). */
public enum PaymentStatus {
    PENDING,
    AUTHORISED,
    CAPTURED,
    CAPTURE_FAILED,
    CANCELLED,
    AUTH_EXPIRED,
    PARTIALLY_REFUNDED,
    REFUNDED,
    FAILED
}
