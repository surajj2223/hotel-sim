package com.hotelops.paymentssim.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotelops.paymentssim.TestcontainersConfiguration;
import com.hotelops.paymentssim.common.auth.PspApiKeyGate;
import com.hotelops.paymentssim.domain.CaptureMode;
import com.hotelops.paymentssim.domain.PspPaymentRepository;
import com.hotelops.paymentssim.domain.PspPaymentStatus;
import com.hotelops.paymentssim.web.dto.CaptureRequest;
import com.hotelops.paymentssim.web.dto.CreatePaymentLinkRequest;
import com.hotelops.paymentssim.web.dto.PaymentLinkResponse;
import com.hotelops.paymentssim.webhook.HmacSigner;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.DockerClientFactory;

/**
 * End-to-end money loop: PSP-013 (authorise trigger), PSP-015 (sync seam for authorise,
 * the customer-checkout stand-in, enabled under {@code test} profile), PSP-016 (signed
 * webhook delivery to {@code core-api}'s receiver), and 1C-a (the real /captures endpoint
 * self-emitting the CAPTURE webhook). A {@link com.hotelops.paymentssim.webhook.RecordingWebhookReceiver}
 * stands in for {@code core-api}; authorise is driven with {@code ?sync=true} (inline),
 * while capture rides the real async self-emit path and the test polls the receiver for it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class AuthoriseTriggerSyncTest {

    @BeforeAll
    static void requireDocker() {
        try {
            DockerClientFactory.instance().client();
        } catch (Exception e) {
            Assumptions.abort("Skipping: no container runtime available.");
        }
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired PspPaymentRepository payments;
    @Autowired ObjectMapper mapper;
    @Autowired HmacSigner signer;          // same bean (and secret) the app signs with

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders authJson() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.add(PspApiKeyGate.HEADER_NAME, "test-shared-secret");
        return h;
    }

    @Test
    void authoriseThenCaptureDeliversSignedWebhooksAndFlipsState() throws Exception {
        try (var receiver = new com.hotelops.paymentssim.webhook.RecordingWebhookReceiver(200)) {
            // 1. PSP-001 — create a PENDING link pointing back at our stub receiver.
            String merchantRef = "MR-loop-" + java.util.UUID.randomUUID();
            var createReq = new CreatePaymentLinkRequest(
                    merchantRef, "SHPR-loop", 70000L, "GBP", CaptureMode.MANUAL, receiver.url());
            var created = rest.postForEntity(url("/v1/payment-links"),
                    new HttpEntity<>(createReq, authJson()), PaymentLinkResponse.class);
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            String linkId = created.getBody().paymentLinkId();

            // 2. PSP-013 + PSP-015 — authorise synchronously.
            var authResp = rest.exchange(
                    url("/v1/test/payment-links/" + linkId + "/authorise?sync=true"),
                    HttpMethod.POST, new HttpEntity<>(authJson()), String.class);
            assertThat(authResp.getStatusCode()).isEqualTo(HttpStatus.OK);

            // 3. AUTHORISATION webhook arrived, signed, with the right shape.
            assertThat(receiver.received).hasSize(1);
            var authHook = receiver.received.get(0);
            assertValidSignature(authHook);
            JsonNode authBody = mapper.readTree(authHook.body());
            assertThat(authBody.get("eventCode").asText()).isEqualTo("AUTHORISATION");
            assertThat(authBody.get("merchantReference").asText()).isEqualTo(merchantRef);
            assertThat(authBody.get("amount").asLong()).isEqualTo(70000L);
            assertThat(authBody.has("authExpiresAt")).isTrue();

            var afterAuth = payments.findByPaymentLinkId(linkId).orElseThrow();
            assertThat(afterAuth.getStatus()).isEqualTo(PspPaymentStatus.AUTHORISED);
            assertThat(afterAuth.getPspReference()).isNotBlank();
            String pspRef = afterAuth.getPspReference();

            // 4. 1C-a — the real /captures endpoint records intent (partial: 54000 of 70000)
            //    AND self-emits the CAPTURE webhook asynchronously after commit. No /v1/test
            //    settle step is needed (and adding one would double-fire the same event).
            var capAck = rest.exchange(
                    url("/v1/payments/" + pspRef + "/captures"),
                    HttpMethod.POST, new HttpEntity<>(new CaptureRequest(54000L), authJson()), String.class);
            assertThat(capAck.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

            // 5. The CAPTURE webhook lands out-of-band — poll the receiver for it.
            awaitReceived(receiver, 2);

            // 6. CAPTURE webhook arrived, signed, idempotencyKey deterministic.
            var capHook = receiver.received.get(1);
            assertValidSignature(capHook);
            JsonNode capBody = mapper.readTree(capHook.body());
            assertThat(capBody.get("eventCode").asText()).isEqualTo("CAPTURE");
            assertThat(capBody.get("amount").asLong()).isEqualTo(54000L);
            assertThat(capBody.get("idempotencyKey").asText()).isEqualTo(pspRef + ":CAPTURE:1");

            var afterCapture = payments.findByPaymentLinkId(linkId).orElseThrow();
            assertThat(afterCapture.getStatus()).isEqualTo(PspPaymentStatus.CAPTURED);
            assertThat(afterCapture.getAmountCaptured()).isEqualTo(54000L);
            assertThat(afterCapture.getPendingCaptureAmount()).isNull();
        }
    }

    /** Poll until the receiver has at least {@code expected} deliveries (async self-emit path). */
    private static void awaitReceived(
            com.hotelops.paymentssim.webhook.RecordingWebhookReceiver receiver, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (receiver.received.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(receiver.received).hasSize(expected);
    }

    private void assertValidSignature(com.hotelops.paymentssim.webhook.RecordingWebhookReceiver.Received hook) {
        String expected = signer.sign(hook.body().getBytes(StandardCharsets.UTF_8));
        assertThat(hook.signature())
                .as("X-PSP-Signature must be a valid HMAC over the raw body")
                .isEqualTo(expected);
    }
}
