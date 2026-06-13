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
 * End-to-end (Part-A side) of the async money loop: PSP-013 (authorise trigger),
 * PSP-015 (sync seam, enabled under {@code test} profile), PSP-016 (signed webhook
 * delivery to {@code core-api}'s receiver). A {@link com.hotelops.paymentssim.webhook.RecordingWebhookReceiver}
 * stands in for {@code core-api}; the test drives authorise→capture with
 * {@code ?sync=true} and asserts each webhook arrived, carried a valid
 * {@code X-PSP-Signature}, and flipped the persisted row — <b>no sleeps</b>.
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

            // 4. PSP-002 — record capture intent (partial: 54000 of 70000).
            var capAck = rest.exchange(
                    url("/v1/payments/" + pspRef + "/captures"),
                    HttpMethod.POST, new HttpEntity<>(new CaptureRequest(54000L), authJson()), String.class);
            assertThat(capAck.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

            // 5. PSP-015 — drive the CAPTURE webhook synchronously.
            var capResp = rest.exchange(
                    url("/v1/test/payments/" + pspRef + "/capture?sync=true"),
                    HttpMethod.POST, new HttpEntity<>(authJson()), String.class);
            assertThat(capResp.getStatusCode()).isEqualTo(HttpStatus.OK);

            // 6. CAPTURE webhook arrived, signed, idempotencyKey deterministic.
            assertThat(receiver.received).hasSize(2);
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

    private void assertValidSignature(com.hotelops.paymentssim.webhook.RecordingWebhookReceiver.Received hook) {
        String expected = signer.sign(hook.body().getBytes(StandardCharsets.UTF_8));
        assertThat(hook.signature())
                .as("X-PSP-Signature must be a valid HMAC over the raw body")
                .isEqualTo(expected);
    }
}
