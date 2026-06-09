package com.hotelops.core.common.auth;

import com.hotelops.core.common.error.HumanAuthRequiredException;
import org.springframework.stereotype.Component;

/**
 * INV-007 — server-side human-authorisation gate.
 *
 * Repercussive writes (createBooking, cancelBookingLine, capturePayment, refundPayment)
 * must supply a human-authorisation token that the caller cannot self-mint.
 *
 * POC mechanism: the caller supplies header {@code X-Human-Auth: <token>} which the UI
 * or agent receives after the user explicitly confirms the action.  The gate validates
 * that the token is present and non-blank; full token verification is intentionally
 * deferred to a later wave (real auth is out of scope for the POC).
 *
 * Safety lives here, in the server — not in client behaviour.
 */
@Component
public class HumanAuthorizationGate {

    public static final String HEADER_NAME = "X-Human-Auth";

    /**
     * Assert that a valid human-auth token was supplied.
     *
     * @param token the value from {@code X-Human-Auth} header (null if absent)
     * @param operation a description of the operation being gated (for the error message)
     * @throws HumanAuthRequiredException if the token is absent or blank
     */
    public void assertAuthorised(String token, String operation) {
        if (token == null || token.isBlank()) {
            throw new HumanAuthRequiredException(operation);
        }
        // POC: token presence is sufficient; no signature/TTL verification in Wave 1
    }
}
