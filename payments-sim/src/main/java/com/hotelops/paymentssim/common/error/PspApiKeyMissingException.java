package com.hotelops.paymentssim.common.error;

/** Thrown when {@code X-PSP-Api-Key} is absent or blank — 401 per WAVE0_05 §2.5. */
public class PspApiKeyMissingException extends RuntimeException {
    public PspApiKeyMissingException() {
        super("Missing or blank X-PSP-Api-Key header");
    }
}
