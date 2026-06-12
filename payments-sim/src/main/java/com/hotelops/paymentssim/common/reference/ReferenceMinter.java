package com.hotelops.paymentssim.common.reference;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * PSP-005 / WAVE0_03 §2 — mints payments-sim-owned references. Stores-never-mints
 * (shopper/merchant) references are echoed verbatim and never go through here.
 *
 * <ul>
 *   <li>{@code paymentLinkId} = {@code "PL-"} + 16 base62 — minted at PSP-001.</li>
 *   <li>{@code pspReference}  = {@code "PSP-"} + 16 base62 — minted at AUTHORISATION
 *       (PSP-013, lands in 1C) and again — distinct — at REFUND (PSP-010, this PR).</li>
 * </ul>
 *
 * Base62 alphabet = 0-9A-Za-z. SecureRandom is sufficient for POC uniqueness; the
 * UNIQUE constraints on the persisted columns guarantee correctness regardless.
 */
@Component
public class ReferenceMinter {

    public static final String PAYMENT_LINK_PREFIX = "PL-";
    public static final String PSP_REFERENCE_PREFIX = "PSP-";
    public static final int TOKEN_LENGTH = 16;

    private static final char[] BASE62 =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    private final SecureRandom random = new SecureRandom();

    public String mintPaymentLinkId() {
        return PAYMENT_LINK_PREFIX + token(TOKEN_LENGTH);
    }

    public String mintPspReference() {
        return PSP_REFERENCE_PREFIX + token(TOKEN_LENGTH);
    }

    private String token(int len) {
        char[] buf = new char[len];
        for (int i = 0; i < len; i++) {
            buf[i] = BASE62[random.nextInt(BASE62.length)];
        }
        return new String(buf);
    }
}
