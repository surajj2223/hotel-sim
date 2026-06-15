package com.hotelops.paymentssim.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotelops.paymentssim.domain.CaptureMode;
import com.hotelops.paymentssim.domain.PspPayment;
import com.hotelops.paymentssim.domain.PspPaymentStatus;
import com.hotelops.paymentssim.web.dto.CaptureAckResponse;
import com.hotelops.paymentssim.web.dto.CaptureRequest;
import com.hotelops.paymentssim.webhook.HmacSigner;
import com.hotelops.paymentssim.webhook.RecordingWebhookReceiver;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 1C-a — proves the money loop closes on the <b>real, always-on</b> request endpoint with
 * <b>production wiring</b>: this test extends {@link AbstractApiTest}, which boots with NO
 * active profile, so the {@code @Profile("test")} {@code TestTriggerController} is absent and
 * every {@code /v1/test/...} route 404s ({@link TestSeamProfileGatingTest} pins that).
 *
 * <p>A {@link RecordingWebhookReceiver} stands in for {@code core-api}'s {@code /webhooks/psp}
 * receiver. The test seeds an {@code AUTHORISED} payment pointing back at it, fires the real
 * {@code POST /v1/payments/{ref}/captures}, and asserts the CAPTURE webhook is self-emitted
 * (signed, deterministic {@code idempotencyKey}) and the row settles — <b>with no
 * {@code /v1/test} call</b>. This is the "loop closes on the real endpoint" proof core-api
 * relies on to reach CAPTURED in the running system.
 */
class RealPathAutoEmitTest extends AbstractApiTest {

    @Autowired ObjectMapper mapper;
    @Autowired HmacSigner signer;   // same bean (and secret) the dispatcher signs with

    @Test
    void realCaptureSelfEmitsSignedWebhookWithoutTestSeam() throws Exception {
        try (var receiver = new RecordingWebhookReceiver(200)) {
            PspPayment p = seedAuthorisedTo(receiver.url(), 70000, 70000);

            var ack = rest.postForEntity(
                    url("/v1/payments/" + p.getPspReference() + "/captures"),
                    entity(new CaptureRequest(54000L)), CaptureAckResponse.class);
            assertThat(ack.getStatusCode().is2xxSuccessful()).isTrue();

            // The webhook lands out-of-band on the async self-emit path — poll for it.
            awaitReceived(receiver, 1);

            var hook = receiver.received.get(0);
            String expectedSig = signer.sign(hook.body().getBytes(StandardCharsets.UTF_8));
            assertThat(hook.signature()).isEqualTo(expectedSig);

            JsonNode body = mapper.readTree(hook.body());
            assertThat(body.get("eventCode").asText()).isEqualTo("CAPTURE");
            assertThat(body.get("amount").asLong()).isEqualTo(54000L);
            assertThat(body.get("merchantReference").asText()).isEqualTo(p.getMerchantReference());
            assertThat(body.get("idempotencyKey").asText())
                    .isEqualTo(p.getPspReference() + ":CAPTURE:1");

            var reloaded = paymentRepository.findByPspReference(p.getPspReference()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(PspPaymentStatus.CAPTURED);
            assertThat(reloaded.getAmountCaptured()).isEqualTo(54000L);
            assertThat(reloaded.getPendingCaptureAmount()).isNull();
        }
    }

    /** Seed an AUTHORISED payment whose callbackUrl points at our in-process receiver. */
    private PspPayment seedAuthorisedTo(String callbackUrl, long requested, long authorised) {
        PspPayment p = new PspPayment();
        p.setMerchantReference("MR-real-" + java.util.UUID.randomUUID());
        p.setShopperReference("SHPR-real-" + java.util.UUID.randomUUID());
        p.setPaymentLinkId(minter.mintPaymentLinkId());
        p.setPspReference(minter.mintPspReference());
        p.setAmountRequested(requested);
        p.setAmountAuthorised(authorised);
        p.setCurrency("GBP");
        p.setStatus(PspPaymentStatus.AUTHORISED);
        p.setCaptureMode(CaptureMode.MANUAL);
        p.setCallbackUrl(callbackUrl);
        return paymentRepository.save(p);
    }

    private static void awaitReceived(RecordingWebhookReceiver receiver, int expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (receiver.received.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(receiver.received).hasSize(expected);
    }
}
