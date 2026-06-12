package com.hotelops.paymentssim.web;

import com.hotelops.paymentssim.common.auth.PspApiKeyGate;
import com.hotelops.paymentssim.service.PspRefundService;
import com.hotelops.paymentssim.web.dto.RefundAckResponse;
import com.hotelops.paymentssim.web.dto.RefundRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** PSP-004 — refund request side. */
@RestController
@RequestMapping("/v1/payments/{pspReference}/refunds")
public class RefundController {

    private final PspRefundService service;
    private final PspApiKeyGate apiKeyGate;

    public RefundController(PspRefundService service, PspApiKeyGate apiKeyGate) {
        this.service = service;
        this.apiKeyGate = apiKeyGate;
    }

    @PostMapping
    public ResponseEntity<RefundAckResponse> requestRefund(
            @RequestHeader(value = PspApiKeyGate.HEADER_NAME, required = false) String apiKey,
            @PathVariable String pspReference,
            @Valid @RequestBody RefundRequest request) {
        apiKeyGate.assertPresent(apiKey);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(service.requestRefund(pspReference, request));
    }
}
