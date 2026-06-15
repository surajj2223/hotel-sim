package com.hotelops.paymentssim.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.hotelops.paymentssim.domain.PspPayment;
import com.hotelops.paymentssim.domain.PspPaymentStatus;
import com.hotelops.paymentssim.domain.PspRefundStatus;
import com.hotelops.paymentssim.web.dto.ApiError;
import com.hotelops.paymentssim.web.dto.RefundAckResponse;
import com.hotelops.paymentssim.web.dto.RefundRequest;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** PSP-004 / PSP-010 — WAVE0_05 §2.4. */
class RefundApiTest extends AbstractApiTest {

    private static final Pattern PSP_REFERENCE = Pattern.compile("^PSP-[0-9A-Za-z]{16}$");

    private String refundUrl(PspPayment p) {
        return url("/v1/payments/" + p.getPspReference() + "/refunds");
    }

    // 1C-a: the real /refunds endpoint persists the PENDING child (tx1, minting a distinct
    // refund pspReference) AND settles + fires the async REFUND webhook (tx2). The tx2 row
    // flips land synchronously even though webhook delivery to the (unreachable) callbackUrl
    // fails fast with no retry: the child becomes REFUNDED and the parent PARTIALLY_REFUNDED.
    // The 202 ack still reports PENDING_REFUND (intent acceptance).
    @Test
    void realRefundRequestSettlesAndMintsDistinctPspReference() {
        PspPayment parent = seedCaptured(70000, 54000);

        var response = rest.postForEntity(refundUrl(parent),
                entity(new RefundRequest(6000L, "MR-RF-01", "guest complaint")),
                RefundAckResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        var body = response.getBody();
        assertThat(body.pspReference()).matches(PSP_REFERENCE);
        assertThat(body.pspReference()).isNotEqualTo(parent.getPspReference());
        assertThat(body.originalReference()).isEqualTo(parent.getPspReference());
        assertThat(body.refundMerchantReference()).isEqualTo("MR-RF-01");
        assertThat(body.amount()).isEqualTo(6000L);
        assertThat(body.currency()).isEqualTo("GBP");
        assertThat(body.status()).isEqualTo(RefundAckResponse.PENDING_REFUND);

        var saved = refundRepository.findByRefundMerchantReference("MR-RF-01").orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(PspRefundStatus.REFUNDED);
        assertThat(saved.getAmount()).isEqualTo(6000L);
        assertThat(saved.getOriginalReference()).isEqualTo(parent.getPspReference());
        assertThat(saved.getPspReference()).isEqualTo(body.pspReference());
        assertThat(saved.getReason()).isEqualTo("guest complaint");

        var reloadedParent = paymentRepository.findByPspReference(parent.getPspReference()).orElseThrow();
        assertThat(reloadedParent.getStatus()).isEqualTo(PspPaymentStatus.PARTIALLY_REFUNDED);
        assertThat(reloadedParent.getAmountRefunded()).isEqualTo(6000L);
    }

    // The first refund settles synchronously (1C-a), so it counts against capturable as a
    // settled (REFUNDED) amount rather than a pending one — the second refund is still
    // rejected because captured − refunded leaves nothing.
    @Test
    void priorRefundsCountAgainstCapturable() {
        PspPayment parent = seedCaptured(70000, 54000);
        rest.postForEntity(refundUrl(parent),
                entity(new RefundRequest(54000L, "MR-RF-full", null)), RefundAckResponse.class);

        var response = rest.postForEntity(refundUrl(parent),
                entity(new RefundRequest(1L, "MR-RF-over", null)), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().code()).isEqualTo("AMOUNT_EXCEEDS_CAPTURABLE");
    }

    @Test
    void singleShotOverRefundReturns422() {
        PspPayment parent = seedCaptured(70000, 54000);

        var response = rest.postForEntity(refundUrl(parent),
                entity(new RefundRequest(60000L, "MR-RF-too-big", null)), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().code()).isEqualTo("AMOUNT_EXCEEDS_CAPTURABLE");
        assertThat(refundRepository.findAll()).isEmpty();
    }

    @Test
    void duplicateRefundMerchantReferenceReturns409() {
        PspPayment parent = seedCaptured(70000, 54000);
        rest.postForEntity(refundUrl(parent),
                entity(new RefundRequest(1000L, "MR-RF-dup", null)), RefundAckResponse.class);

        var response = rest.postForEntity(refundUrl(parent),
                entity(new RefundRequest(2000L, "MR-RF-dup", null)), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("DUPLICATE_REFUND_MERCHANT_REFERENCE");
        assertThat(refundRepository.findAll()).hasSize(1);
    }

    @Test
    void refundingUncapturedPaymentReturns422() {
        PspPayment parent = seedAuthorised(70000, 70000);

        var response = rest.postForEntity(refundUrl(parent),
                entity(new RefundRequest(1L, "MR-RF-none", null)), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().code()).isEqualTo("AMOUNT_EXCEEDS_CAPTURABLE");
    }

    @Test
    void unknownPspReferenceReturns404() {
        var response = rest.postForEntity(
                url("/v1/payments/PSP-unknown/refunds"),
                entity(new RefundRequest(1L, "MR-RF-x", null)), ApiError.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void missingApiKeyReturns401() {
        PspPayment parent = seedCaptured(70000, 54000);
        var unauthEntity = new org.springframework.http.HttpEntity<>(
                new RefundRequest(1L, "MR-RF-noauth", null), unauthenticatedHeaders());
        var response = rest.postForEntity(refundUrl(parent), unauthEntity, ApiError.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().code()).isEqualTo("PSP_API_KEY_MISSING");
    }
}
