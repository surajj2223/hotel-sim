package com.hotelops.paymentssim.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * WHK-014 — HMAC-SHA256 over the raw webhook body, hex-encoded. The shared secret
 * is configured via {@code psp-sim.webhook-secret} (env {@code PSP_WEBHOOK_SECRET});
 * {@code core-api} verifies with the same key on the inbound side.
 *
 * <p>The header name is {@link #HEADER_NAME}. POC scheme: bare lowercase hex; no
 * timestamp, no replay window. Mirrors the {@link com.hotelops.paymentssim.common.auth.PspApiKeyGate}
 * coarse-auth posture.
 */
@Component
public class HmacSigner {

    public static final String HEADER_NAME = "X-PSP-Signature";
    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secret;

    public HmacSigner(@Value("${psp-sim.webhook-secret:dev-webhook-secret}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("psp-sim.webhook-secret must be configured");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return hex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC signing failed", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
