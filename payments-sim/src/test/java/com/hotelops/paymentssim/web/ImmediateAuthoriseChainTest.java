package com.hotelops.paymentssim.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotelops.paymentssim.TestcontainersConfiguration;
import com.hotelops.paymentssim.common.auth.PspApiKeyGate;
import com.hotelops.paymentssim.domain.CaptureMode;
import com.hotelops.paymentssim.domain.PspPaymentRepository;
import com.hotelops.paymentssim.domain.PspPaymentStatus;
import com.hotelops.paymentssim.web.dto.CreatePaymentLinkRequest;
import com.hotelops.paymentssim.web.dto.PaymentLinkResponse;
import com.hotelops.paymentssim.webhook.HmacSigner;
import com.hotelops.paymentssim.webhook.RecordingWebhookReceiver;
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
 * Slice B2 — IMMEDIATE authorise drives the two-event auth-and-capture-together path
 * (ENM-004): one {@code AUTHORISATION} (WHK-006) immediately followed by one
 * {@code CAPTURE} (WHK-007), both via the existing dispatch machinery, no new event
 * code. MANUAL is the regression guard: a single {@code AUTHORISATION}, capture deferred
 * to a separate trigger. Drives {@code ?sync=true} and asserts against the recording
 * receiver — no sleeps.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ImmediateAuthoriseChainTest {

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
    @Autowired HmacSigner signer;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders authJson() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.add(PspApiKeyGate.HEADER_NAME, "test-shared-secret");
        return h;
    }

    private String createLink(String merchantRef, CaptureMode mode, RecordingWebhookReceiver receiver) {
        var createReq = new CreatePaymentLinkRequest(
                merchantRef, "SHPR-imm", 60000L, "GBP", mode, receiver.url());
        var created = rest.postForEntity(url("/v1/payment-links"),
                new HttpEntity<>(createReq, authJson()), PaymentLinkResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().paymentLinkId();
    }

    @Test
    void immediateAuthoriseEmitsAuthorisationThenCapture() throws Exception {
        try (var receiver = new RecordingWebhookReceiver(200)) {
            String merchantRef = "MR-imm-" + java.util.UUID.randomUUID();
            String linkId = createLink(merchantRef, CaptureMode.IMMEDIATE, receiver);

            // Authorise synchronously — IMMEDIATE must chain straight into CAPTURE.
            var authResp = rest.exchange(
                    url("/v1/test/payment-links/" + linkId + "/authorise?sync=true"),
                    HttpMethod.POST, new HttpEntity<>(authJson()), String.class);
            assertThat(authResp.getStatusCode()).isEqualTo(HttpStatus.OK);

            // Two webhooks arrived, in order: AUTHORISATION then CAPTURE.
            assertThat(receiver.received).hasSize(2);

            var authHook = receiver.received.get(0);
            assertValidSignature(authHook);
            JsonNode authBody = mapper.readTree(authHook.body());
            assertThat(authBody.get("eventCode").asText()).isEqualTo("AUTHORISATION");
            assertThat(authBody.get("merchantReference").asText()).isEqualTo(merchantRef);
            assertThat(authBody.get("amount").asLong()).isEqualTo(60000L);
            assertThat(authBody.has("authExpiresAt")).isTrue();

            var afterAuth = payments.findByPaymentLinkId(linkId).orElseThrow();
            String pspRef = afterAuth.getPspReference();
            assertThat(pspRef).isNotBlank();

            var capHook = receiver.received.get(1);
            assertValidSignature(capHook);
            JsonNode capBody = mapper.readTree(capHook.body());
            assertThat(capBody.get("eventCode").asText()).isEqualTo("CAPTURE");
            assertThat(capBody.get("amount").asLong()).isEqualTo(60000L);  // full authorised
            // Distinct seq/idempotency key from the AUTHORISATION — no collision.
            assertThat(authBody.get("idempotencyKey").asText()).isEqualTo(pspRef + ":AUTHORISATION:1");
            assertThat(capBody.get("idempotencyKey").asText()).isEqualTo(pspRef + ":CAPTURE:1");

            var afterCapture = payments.findByPaymentLinkId(linkId).orElseThrow();
            assertThat(afterCapture.getStatus()).isEqualTo(PspPaymentStatus.CAPTURED);
            assertThat(afterCapture.getAmountCaptured()).isEqualTo(afterCapture.getAmountAuthorised());
            assertThat(afterCapture.getAmountCaptured()).isEqualTo(60000L);
            assertThat(afterCapture.getPendingCaptureAmount()).isNull();
        }
    }

    @Test
    void manualAuthoriseEmitsOnlyAuthorisation() throws Exception {
        try (var receiver = new RecordingWebhookReceiver(200)) {
            String merchantRef = "MR-man-" + java.util.UUID.randomUUID();
            String linkId = createLink(merchantRef, CaptureMode.MANUAL, receiver);

            var authResp = rest.exchange(
                    url("/v1/test/payment-links/" + linkId + "/authorise?sync=true"),
                    HttpMethod.POST, new HttpEntity<>(authJson()), String.class);
            assertThat(authResp.getStatusCode()).isEqualTo(HttpStatus.OK);

            // MANUAL: exactly one event, no auto-capture (two-step preserved).
            assertThat(receiver.received).hasSize(1);
            JsonNode authBody = mapper.readTree(receiver.received.get(0).body());
            assertThat(authBody.get("eventCode").asText()).isEqualTo("AUTHORISATION");

            var afterAuth = payments.findByPaymentLinkId(linkId).orElseThrow();
            assertThat(afterAuth.getStatus()).isEqualTo(PspPaymentStatus.AUTHORISED);
            assertThat(afterAuth.getAmountCaptured()).isZero();
        }
    }

    private void assertValidSignature(RecordingWebhookReceiver.Received hook) {
        String expected = signer.sign(hook.body().getBytes(StandardCharsets.UTF_8));
        assertThat(hook.signature())
                .as("X-PSP-Signature must be a valid HMAC over the raw body")
                .isEqualTo(expected);
    }
}
