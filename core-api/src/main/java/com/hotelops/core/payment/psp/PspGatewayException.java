package com.hotelops.core.payment.psp;

/**
 * PSP-007 — any failure of an outbound {@code core-api → payments-sim} call: connection
 * refused, read timeout, non-2xx, or a malformed/unreadable response body. Carries a
 * human-readable reason that {@code GlobalExceptionHandler} surfaces to the operator as a
 * {@code 502}-class {@code ApiError} (including the underlying {@code payments-sim} reason
 * verbatim where available).
 *
 * <p>There is deliberately no retry, fallback, or partial-state behaviour (PSP-008): the
 * payment row is left in its pre-call state and the operator re-issues the action, which
 * mints a fresh {@code merchantReference}.
 */
public class PspGatewayException extends RuntimeException {

    public PspGatewayException(String message) {
        super(message);
    }

    public PspGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
