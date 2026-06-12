package com.hotelops.paymentssim.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.hotelops.paymentssim.domain.CaptureMode;
import com.hotelops.paymentssim.domain.PspPaymentStatus;
import com.hotelops.paymentssim.web.dto.ApiError;
import com.hotelops.paymentssim.web.dto.CreatePaymentLinkRequest;
import com.hotelops.paymentssim.web.dto.PaymentLinkResponse;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** PSP-001 / PSP-005 — WAVE0_05 §2.1. */
class PaymentLinkApiTest extends AbstractApiTest {

    private static final Pattern PAYMENT_LINK_PATTERN = Pattern.compile("^PL-[0-9A-Za-z]{16}$");

    private CreatePaymentLinkRequest req(String merchantRef) {
        return new CreatePaymentLinkRequest(
                merchantRef, "SHPR-abc", 70000L, "GBP", CaptureMode.MANUAL,
                "http://core-api:8080/webhooks/psp");
    }

    @Test
    void createsLinkPendingAndMintsPaymentLinkId() {
        var response = rest.postForEntity(
                url("/v1/payment-links"), entity(req("MR-001")), PaymentLinkResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        PaymentLinkResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.paymentLinkId()).matches(PAYMENT_LINK_PATTERN);
        assertThat(body.merchantReference()).isEqualTo("MR-001");
        assertThat(body.shopperReference()).isEqualTo("SHPR-abc");
        assertThat(body.status()).isEqualTo(PspPaymentStatus.PENDING);
        assertThat(body.amount()).isEqualTo(70000L);
        assertThat(body.currency()).isEqualTo("GBP");
        assertThat(body.hostedUrl()).endsWith("/checkout/" + body.paymentLinkId());

        var saved = paymentRepository.findByMerchantReference("MR-001").orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(PspPaymentStatus.PENDING);
        assertThat(saved.getAmountAuthorised()).isZero();
        assertThat(saved.getAmountCaptured()).isZero();
        assertThat(saved.getAmountRefunded()).isZero();
        assertThat(saved.getPaymentLinkId()).isEqualTo(body.paymentLinkId());
        assertThat(saved.getPspReference()).isNull();
        assertThat(saved.getCallbackUrl()).isEqualTo("http://core-api:8080/webhooks/psp");
    }

    @Test
    void duplicateMerchantReferenceReturns409() {
        rest.postForEntity(url("/v1/payment-links"), entity(req("MR-dup")), PaymentLinkResponse.class);
        var response = rest.postForEntity(url("/v1/payment-links"), entity(req("MR-dup")), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("DUPLICATE_MERCHANT_REFERENCE");
        assertThat(paymentRepository.findAll()).hasSize(1);
    }

    @Test
    void missingApiKeyReturns401() {
        var entity = new org.springframework.http.HttpEntity<>(req("MR-noauth"), unauthenticatedHeaders());
        var response = rest.postForEntity(url("/v1/payment-links"), entity, ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().code()).isEqualTo("PSP_API_KEY_MISSING");
        assertThat(paymentRepository.findAll()).isEmpty();
    }

    @Test
    void zeroAmountReturns400() {
        var bad = new CreatePaymentLinkRequest(
                "MR-zero", "SHPR-zero", 0L, "GBP", CaptureMode.MANUAL, null);
        var response = rest.postForEntity(url("/v1/payment-links"), entity(bad), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("BAD_REQUEST");
    }

    @Test
    void omittedCallbackUrlUsesDefault() {
        var req = new CreatePaymentLinkRequest(
                "MR-default-cb", "SHPR-x", 10000L, "GBP", CaptureMode.IMMEDIATE, null);
        var response = rest.postForEntity(url("/v1/payment-links"), entity(req), PaymentLinkResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var saved = paymentRepository.findByMerchantReference("MR-default-cb").orElseThrow();
        assertThat(saved.getCallbackUrl()).isNotBlank();
    }
}
