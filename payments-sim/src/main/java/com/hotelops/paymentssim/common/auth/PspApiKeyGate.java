package com.hotelops.paymentssim.common.auth;

import com.hotelops.paymentssim.common.error.PspApiKeyMissingException;
import org.springframework.stereotype.Component;

/**
 * PSP-005 note / WAVE0_05 §2 — coarse mutual-auth gate between core-api and payments-sim.
 *
 * POC mechanism: every request to {@code /v1/payment-links} and
 * {@code /v1/payments/...} carries {@code X-PSP-Api-Key: <shared POC secret>}. The gate
 * checks presence-only — full cryptographic verification is intentionally deferred
 * (same posture as core-api's {@code HumanAuthorizationGate}).
 *
 * Separate from the webhook signature ({@code X-PSP-Signature}), which lands in 1C.
 */
@Component
public class PspApiKeyGate {

    public static final String HEADER_NAME = "X-PSP-Api-Key";

    public void assertPresent(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new PspApiKeyMissingException();
        }
    }
}
