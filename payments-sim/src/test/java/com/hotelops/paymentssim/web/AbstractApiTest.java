package com.hotelops.paymentssim.web;

import com.hotelops.paymentssim.TestcontainersConfiguration;
import com.hotelops.paymentssim.common.auth.PspApiKeyGate;
import com.hotelops.paymentssim.common.reference.ReferenceMinter;
import com.hotelops.paymentssim.domain.PspPayment;
import com.hotelops.paymentssim.domain.PspPaymentRepository;
import com.hotelops.paymentssim.domain.PspPaymentStatus;
import com.hotelops.paymentssim.domain.PspRefundRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.DockerClientFactory;

/** Shared plumbing: testcontainers + REST template + seeding helpers. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
abstract class AbstractApiTest {

    static final String API_KEY = "test-shared-secret";

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
    @Autowired PspPaymentRepository paymentRepository;
    @Autowired PspRefundRepository refundRepository;
    @Autowired ReferenceMinter minter;

    @BeforeEach
    void wipeTables() {
        refundRepository.deleteAll();
        paymentRepository.deleteAll();
    }

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    HttpHeaders authHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.add(PspApiKeyGate.HEADER_NAME, API_KEY);
        return h;
    }

    HttpHeaders unauthenticatedHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    <T> HttpEntity<T> entity(T body) {
        return new HttpEntity<>(body, authHeaders());
    }

    HttpEntity<Void> emptyAuthEntity() {
        return new HttpEntity<>(authHeaders());
    }

    /**
     * Seed a PspPayment in AUTHORISED state (or beyond) so capture/cancel/refund tests
     * can exercise their validation paths without depending on PSP-013's trigger (1C).
     */
    @Transactional
    protected PspPayment seedAuthorised(long amountRequested, long amountAuthorised) {
        PspPayment p = new PspPayment();
        p.setMerchantReference("MR-seed-" + java.util.UUID.randomUUID());
        p.setShopperReference("SHPR-seed-" + java.util.UUID.randomUUID());
        p.setPaymentLinkId(minter.mintPaymentLinkId());
        p.setPspReference(minter.mintPspReference());
        p.setAmountRequested(amountRequested);
        p.setAmountAuthorised(amountAuthorised);
        p.setCurrency("GBP");
        p.setStatus(PspPaymentStatus.AUTHORISED);
        p.setCaptureMode(com.hotelops.paymentssim.domain.CaptureMode.MANUAL);
        p.setCallbackUrl("http://core-api:8080/webhooks/psp");
        return paymentRepository.save(p);
    }

    /** Seed an AUTHORISED+CAPTURED row for refund tests. */
    @Transactional
    protected PspPayment seedCaptured(long amountAuthorised, long amountCaptured) {
        PspPayment p = seedAuthorised(amountAuthorised, amountAuthorised);
        p.setAmountCaptured(amountCaptured);
        p.setStatus(PspPaymentStatus.CAPTURED);
        return paymentRepository.save(p);
    }
}
