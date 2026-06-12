package com.hotelops.paymentssim.domain;

/** Mirrors ENM-005 PaymentStatus (subset persisted by payments-sim). */
public enum PspPaymentStatus {
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
