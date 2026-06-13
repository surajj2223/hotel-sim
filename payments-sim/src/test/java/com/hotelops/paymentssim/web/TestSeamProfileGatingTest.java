package com.hotelops.paymentssim.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * PSP-015 / WHK-015 / Trap B / CLAUDE.md — the {@code /v1/test/...} seam is
 * <b>unreachable in the running system</b>. This test boots {@code payments-sim} with
 * NO active profile (i.e. {@link AbstractApiTest} = the production wiring); the
 * {@code @Profile("test")} {@code TestTriggerController} is therefore not registered,
 * so every trigger route — including the {@code ?sync=true} seam — returns 404.
 *
 * <p>The enabled-path counterpart (the same routes returning 2xx under
 * {@code @ActiveProfiles("test")}) is proven by {@link AuthoriseTriggerSyncTest}.
 */
class TestSeamProfileGatingTest extends AbstractApiTest {

    @Test
    void authoriseTriggerIsUnreachableWithoutTestProfile() {
        var response = rest.exchange(
                url("/v1/test/payment-links/PL-anything/authorise?sync=true"),
                org.springframework.http.HttpMethod.POST, emptyAuthEntity(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void captureTriggerIsUnreachableWithoutTestProfile() {
        var response = rest.exchange(
                url("/v1/test/payments/PSP-anything/capture?sync=true"),
                org.springframework.http.HttpMethod.POST, emptyAuthEntity(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void cancelTriggerIsUnreachableWithoutTestProfile() {
        var response = rest.exchange(
                url("/v1/test/payments/PSP-anything/cancel"),
                org.springframework.http.HttpMethod.POST, emptyAuthEntity(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void refundSettleTriggerIsUnreachableWithoutTestProfile() {
        var response = rest.exchange(
                url("/v1/test/refunds/MR-RF-anything/settle"),
                org.springframework.http.HttpMethod.POST, emptyAuthEntity(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
