package com.hotelops.core.payment.psp;

import com.hotelops.core.payment.psp.dto.PspCaptureRequest;
import com.hotelops.core.payment.psp.dto.PspCreateLinkRequest;
import com.hotelops.core.payment.psp.dto.PspPaymentLinkResponse;
import com.hotelops.core.payment.psp.dto.PspRefundRequest;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * PSP-001..004 — the outbound {@code core-api → payments-sim} HTTP client (the "left half"
 * of payments). Owns the {@code RestClient}; carries the {@code X-PSP-Api-Key} coarse-auth
 * header (WAVE0_05 §2).
 *
 * <p><b>Not {@code @Transactional}, and must never be called from inside an open
 * transaction</b> (PSP-006). The {@code PaymentOrchestrator} sequences
 * {@code tx1 (commit) → this call → tx2}; this bean only does the network round-trip.
 *
 * <p><b>Fail-loud, no retry</b> (PSP-007 / PSP-008): every failure mode — connection
 * refused / timeout, non-2xx, malformed body — is translated to a single
 * {@link PspGatewayException}. No retry tick, circuit-breaker, or DLQ.
 */
@Component
public class PspGateway {

    /** WAVE0_05 §2 coarse mutual-auth header. */
    static final String API_KEY_HEADER = "X-PSP-Api-Key";

    private final RestClient client;

    public PspGateway(@Value("${payments-sim.base-url:http://localhost:8081}") String baseUrl,
                      @Value("${payments-sim.api-key:dev-psp-api-key}") String apiKey,
                      RestClient.Builder builder) {
        // Bounded timeouts so an unreachable/slow PSP fails loudly and fast rather than
        // pinning a request thread (PSP-007). No retries are configured anywhere.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.client = builder
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader(API_KEY_HEADER, apiKey)
                .build();
    }

    /** PSP-001 — create a payment link; returns the minted {@code paymentLinkId}. */
    public PspPaymentLinkResponse createLink(PspCreateLinkRequest request) {
        return call("create-link", () -> client.post()
                .uri("/v1/payment-links")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(PspPaymentLinkResponse.class));
    }

    /** PSP-002 — request capture (full when {@code amount} null). 202; state lands on webhook. */
    public void requestCapture(String pspReference, Long amount) {
        call("capture", () -> client.post()
                .uri("/v1/payments/{ref}/captures", pspReference)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PspCaptureRequest(amount))
                .retrieve()
                .toBodilessEntity());
    }

    /** PSP-003 — request cancellation of an uncaptured authorisation. 202. */
    public void requestCancellation(String pspReference) {
        call("cancellation", () -> client.post()
                .uri("/v1/payments/{ref}/cancellations", pspReference)
                .retrieve()
                .toBodilessEntity());
    }

    /** PSP-004 — request a refund against the parent {@code pspReference}. 202. */
    public void requestRefund(String parentPspReference, long amount,
                              String refundMerchantReference, String reason) {
        call("refund", () -> client.post()
                .uri("/v1/payments/{ref}/refunds", parentPspReference)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PspRefundRequest(amount, refundMerchantReference, reason))
                .retrieve()
                .toBodilessEntity());
    }

    /**
     * PSP-007 failure mapping (WAVE0_05 §3.2). Every outbound failure becomes one
     * {@link PspGatewayException}; the caller leaves the payment row in its pre-call state.
     */
    private <T> T call(String action, Supplier<T> op) {
        try {
            return op.get();
        } catch (RestClientResponseException e) {            // non-2xx from payments-sim
            throw new PspGatewayException(
                    "PSP rejected " + action + ": " + e.getStatusCode().value()
                            + " " + e.getResponseBodyAsString(), e);
        } catch (ResourceAccessException e) {                // connection refused / timeout / IO
            throw new PspGatewayException(
                    "PSP unreachable for " + action + ": " + rootMessage(e), e);
        } catch (RestClientException e) {                    // malformed / unconvertible body
            throw new PspGatewayException(
                    "PSP malformed response for " + action + ": " + rootMessage(e), e);
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }
}
