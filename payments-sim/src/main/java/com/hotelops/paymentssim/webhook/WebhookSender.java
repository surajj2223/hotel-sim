package com.hotelops.paymentssim.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * PSP-016 — POSTs a single webhook envelope to {@code core-api}'s receiver
 * ({@code /webhooks/psp}, API-013) with the {@link HmacSigner#HEADER_NAME} header.
 *
 * <p><b>Single attempt, no retry</b> (PSP-007 / PSP-008). Any failure — connection
 * refused, read timeout, non-2xx, malformed body — is logged with the
 * {@code idempotencyKey} for operator inspection and surfaced to the caller via
 * {@link DispatchResult#delivered()}. <b>No internal retry, no DLQ, no
 * circuit-breaker.</b>
 *
 * <p>Uses the JDK {@link HttpClient} directly (not {@code RestClient}) so retry
 * semantics, message converters, and interceptor stacks can't be silently injected
 * by Spring auto-config.
 */
@Component
public class WebhookSender {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookSender.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final HmacSigner signer;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public WebhookSender(HmacSigner signer, ObjectMapper mapper) {
        this.signer = signer;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    public DispatchResult send(WebhookEnvelope envelope, String callbackUrl) {
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(envelope);
        } catch (JsonProcessingException e) {
            LOG.error("webhook serialization failed: idempotencyKey={}", envelope.idempotencyKey(), e);
            return DispatchResult.serializationFailure();
        }
        String signature = signer.sign(body);

        HttpRequest req = HttpRequest.newBuilder(URI.create(callbackUrl))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header(HmacSigner.HEADER_NAME, signature)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            boolean ok = code >= 200 && code < 300;
            if (!ok) {
                LOG.warn("webhook non-2xx: code={} idempotencyKey={} url={} body={}",
                        code, envelope.idempotencyKey(), callbackUrl, resp.body());
            }
            return new DispatchResult(ok, code, resp.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warn("webhook delivery failed: idempotencyKey={} url={} reason={}",
                    envelope.idempotencyKey(), callbackUrl, e.toString());
            return DispatchResult.deliveryFailure(e.toString());
        }
    }

    public record DispatchResult(boolean delivered, int statusCode, String body) {
        public static DispatchResult deliveryFailure(String reason) {
            return new DispatchResult(false, 0, reason);
        }
        public static DispatchResult serializationFailure() {
            return new DispatchResult(false, 0, "serialization_failed");
        }
    }
}
