package com.hotelops.core.common.error;

/**
 * Thrown when an inbound PSP webhook arrives without a valid {@code X-PSP-Signature}.
 * Maps to HTTP 401 via {@link com.hotelops.core.web.GlobalExceptionHandler}.
 * Enforces WHK-014: the webhook is signature-authenticated, not human-gated.
 */
public class InvalidPspSignatureException extends RuntimeException {

    public InvalidPspSignatureException(String message) {
        super(message);
    }
}
