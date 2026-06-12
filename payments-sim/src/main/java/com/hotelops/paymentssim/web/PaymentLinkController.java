package com.hotelops.paymentssim.web;

import com.hotelops.paymentssim.common.auth.PspApiKeyGate;
import com.hotelops.paymentssim.service.PspPaymentService;
import com.hotelops.paymentssim.web.dto.CreatePaymentLinkRequest;
import com.hotelops.paymentssim.web.dto.PaymentLinkResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** PSP-001 — {@code POST /v1/payment-links}. */
@RestController
@RequestMapping("/v1/payment-links")
public class PaymentLinkController {

    private final PspPaymentService service;
    private final PspApiKeyGate apiKeyGate;

    public PaymentLinkController(PspPaymentService service, PspApiKeyGate apiKeyGate) {
        this.service = service;
        this.apiKeyGate = apiKeyGate;
    }

    @PostMapping
    public ResponseEntity<PaymentLinkResponse> create(
            @RequestHeader(value = PspApiKeyGate.HEADER_NAME, required = false) String apiKey,
            @Valid @RequestBody CreatePaymentLinkRequest request) {
        apiKeyGate.assertPresent(apiKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createLink(request));
    }
}
