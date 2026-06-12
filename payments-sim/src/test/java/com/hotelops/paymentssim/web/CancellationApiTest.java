package com.hotelops.paymentssim.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.hotelops.paymentssim.domain.PspPayment;
import com.hotelops.paymentssim.domain.PspPaymentStatus;
import com.hotelops.paymentssim.web.dto.ApiError;
import com.hotelops.paymentssim.web.dto.CancellationAckResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** PSP-003 — WAVE0_05 §2.3. */
class CancellationApiTest extends AbstractApiTest {

    private String cancelUrl(PspPayment p) {
        return url("/v1/payments/" + p.getPspReference() + "/cancellations");
    }

    @Test
    void recordsCancellationIntentReturns202() {
        PspPayment p = seedAuthorised(70000, 70000);

        var response = rest.postForEntity(cancelUrl(p), emptyAuthEntity(), CancellationAckResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        var body = response.getBody();
        assertThat(body.pspReference()).isEqualTo(p.getPspReference());
        assertThat(body.merchantReference()).isEqualTo(p.getMerchantReference());
        assertThat(body.status()).isEqualTo(CancellationAckResponse.PENDING_CANCELLATION);

        var reloaded = paymentRepository.findByPspReference(p.getPspReference()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PspPaymentStatus.AUTHORISED);
        assertThat(reloaded.isCancellationPending()).isTrue();
    }

    @Test
    void cancelAfterCapturedAmountReturns409() {
        PspPayment p = seedCaptured(70000, 54000);

        var response = rest.exchange(
                cancelUrl(p), org.springframework.http.HttpMethod.POST, emptyAuthEntity(), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("CANCEL_NOT_PERMITTED");
    }

    @Test
    void cancelWithQueuedCaptureReturns409() {
        PspPayment p = seedAuthorised(70000, 70000);
        p.setPendingCaptureAmount(50000L);
        paymentRepository.save(p);

        var response = rest.postForEntity(cancelUrl(p), emptyAuthEntity(), ApiError.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("CANCEL_NOT_PERMITTED");
    }

    @Test
    void secondCancelRequestReturns409() {
        PspPayment p = seedAuthorised(70000, 70000);
        rest.postForEntity(cancelUrl(p), emptyAuthEntity(), CancellationAckResponse.class);

        var response = rest.postForEntity(cancelUrl(p), emptyAuthEntity(), ApiError.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("CANCEL_ALREADY_REQUESTED");
    }

    @Test
    void unknownPspReferenceReturns404() {
        var response = rest.postForEntity(
                url("/v1/payments/PSP-nope/cancellations"), emptyAuthEntity(), ApiError.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void missingApiKeyReturns401() {
        PspPayment p = seedAuthorised(70000, 70000);
        var unauthEntity = new org.springframework.http.HttpEntity<>(unauthenticatedHeaders());
        var response = rest.postForEntity(cancelUrl(p), unauthEntity, ApiError.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().code()).isEqualTo("PSP_API_KEY_MISSING");
    }
}
