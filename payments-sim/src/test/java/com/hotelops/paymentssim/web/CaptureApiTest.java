package com.hotelops.paymentssim.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.hotelops.paymentssim.domain.PspPayment;
import com.hotelops.paymentssim.domain.PspPaymentStatus;
import com.hotelops.paymentssim.web.dto.ApiError;
import com.hotelops.paymentssim.web.dto.CaptureAckResponse;
import com.hotelops.paymentssim.web.dto.CaptureRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** PSP-002 — WAVE0_05 §2.2. */
class CaptureApiTest extends AbstractApiTest {

    private String captureUrl(PspPayment p) {
        return url("/v1/payments/" + p.getPspReference() + "/captures");
    }

    // 1C-a: the real /captures endpoint now records intent (tx1) AND settles + fires the
    // async CAPTURE webhook (tx2). With production wiring (no `test` profile) the webhook
    // POSTs to the seeded callbackUrl (core-api), which is unreachable here — the delivery
    // fails fast and is logged with no retry, but the synchronous tx2 row flip still lands,
    // so the post-request row is CAPTURED. The 202 ack still reports PENDING_CAPTURE (it
    // reflects intent acceptance; the webhook is the authoritative settlement notification).
    @Test
    void realCaptureRequestSettlesAndReturns202() {
        PspPayment p = seedAuthorised(70000, 70000);

        var response = rest.postForEntity(captureUrl(p),
                entity(new CaptureRequest(54000L)), CaptureAckResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        var body = response.getBody();
        assertThat(body.pspReference()).isEqualTo(p.getPspReference());
        assertThat(body.merchantReference()).isEqualTo(p.getMerchantReference());
        assertThat(body.amount()).isEqualTo(54000L);
        assertThat(body.status()).isEqualTo(CaptureAckResponse.PENDING_CAPTURE);

        var reloaded = paymentRepository.findByPspReference(p.getPspReference()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PspPaymentStatus.CAPTURED);
        assertThat(reloaded.getPendingCaptureAmount()).isNull();
        assertThat(reloaded.getAmountCaptured()).isEqualTo(54000L);
    }

    @Test
    void nullAmountCapturesFullAuthorisedAmount() {
        PspPayment p = seedAuthorised(70000, 70000);

        var response = rest.postForEntity(captureUrl(p),
                entity(new CaptureRequest(null)), CaptureAckResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().amount()).isEqualTo(70000L);
        var reloaded = paymentRepository.findByPspReference(p.getPspReference()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PspPaymentStatus.CAPTURED);
        assertThat(reloaded.getPendingCaptureAmount()).isNull();
        assertThat(reloaded.getAmountCaptured()).isEqualTo(70000L);
    }

    @Test
    void amountExceedingAuthorisedReturns422() {
        PspPayment p = seedAuthorised(70000, 70000);

        var response = rest.postForEntity(captureUrl(p),
                entity(new CaptureRequest(80000L)), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().code()).isEqualTo("AMOUNT_EXCEEDS_AUTHORISED");
        assertThat(paymentRepository.findByPspReference(p.getPspReference()).orElseThrow()
                .getPendingCaptureAmount()).isNull();
    }

    // INV-005 single-capture is still enforced, but since the first real /captures now
    // settles synchronously to CAPTURED, the second request is rejected by the
    // "not AUTHORISED" guard (INVALID_STATE) rather than the queued-capture guard
    // (ALREADY_CAPTURED). Both are 409; the first capture is unaffected.
    @Test
    void secondCaptureRequestReturns409() {
        PspPayment p = seedAuthorised(70000, 70000);
        rest.postForEntity(captureUrl(p), entity(new CaptureRequest(54000L)), CaptureAckResponse.class);

        var response = rest.postForEntity(captureUrl(p),
                entity(new CaptureRequest(10000L)), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("INVALID_STATE");
        var reloaded = paymentRepository.findByPspReference(p.getPspReference()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PspPaymentStatus.CAPTURED);
        assertThat(reloaded.getAmountCaptured()).isEqualTo(54000L);
    }

    @Test
    void capturingUnauthorisedPaymentReturns409() {
        PspPayment p = new PspPayment();
        p.setMerchantReference("MR-pending");
        p.setShopperReference("SHPR-x");
        p.setPaymentLinkId(minter.mintPaymentLinkId());
        p.setPspReference(minter.mintPspReference());
        p.setAmountRequested(10000L);
        p.setCurrency("GBP");
        p.setCaptureMode(com.hotelops.paymentssim.domain.CaptureMode.MANUAL);
        p.setCallbackUrl("http://core-api:8080/webhooks/psp");
        p.setStatus(PspPaymentStatus.PENDING);
        paymentRepository.save(p);

        var response = rest.postForEntity(captureUrl(p),
                entity(new CaptureRequest(1L)), ApiError.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("INVALID_STATE");
    }

    @Test
    void unknownPspReferenceReturns404() {
        var response = rest.postForEntity(
                url("/v1/payments/PSP-doesnotexist/captures"),
                entity(new CaptureRequest(1L)), ApiError.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void missingApiKeyReturns401() {
        PspPayment p = seedAuthorised(70000, 70000);
        var unauthEntity = new org.springframework.http.HttpEntity<>(
                new CaptureRequest(1L), unauthenticatedHeaders());
        var response = rest.postForEntity(captureUrl(p), unauthEntity, ApiError.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().code()).isEqualTo("PSP_API_KEY_MISSING");
    }
}
