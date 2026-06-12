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

    @Test
    void recordsCaptureIntentReturns202() {
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
        assertThat(reloaded.getStatus()).isEqualTo(PspPaymentStatus.AUTHORISED);
        assertThat(reloaded.getPendingCaptureAmount()).isEqualTo(54000L);
        assertThat(reloaded.getAmountCaptured()).isZero();
    }

    @Test
    void nullAmountCapturesFullAuthorisedAmount() {
        PspPayment p = seedAuthorised(70000, 70000);

        var response = rest.postForEntity(captureUrl(p),
                entity(new CaptureRequest(null)), CaptureAckResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().amount()).isEqualTo(70000L);
        assertThat(paymentRepository.findByPspReference(p.getPspReference()).orElseThrow()
                .getPendingCaptureAmount()).isEqualTo(70000L);
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

    @Test
    void secondCaptureRequestReturns409() {
        PspPayment p = seedAuthorised(70000, 70000);
        rest.postForEntity(captureUrl(p), entity(new CaptureRequest(54000L)), CaptureAckResponse.class);

        var response = rest.postForEntity(captureUrl(p),
                entity(new CaptureRequest(10000L)), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("ALREADY_CAPTURED");
        assertThat(paymentRepository.findByPspReference(p.getPspReference()).orElseThrow()
                .getPendingCaptureAmount()).isEqualTo(54000L);
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
