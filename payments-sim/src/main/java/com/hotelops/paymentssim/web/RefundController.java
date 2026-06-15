package com.hotelops.paymentssim.web;

import com.hotelops.paymentssim.common.auth.PspApiKeyGate;
import com.hotelops.paymentssim.service.PspRefundService;
import com.hotelops.paymentssim.web.dto.RefundAckResponse;
import com.hotelops.paymentssim.web.dto.RefundRequest;
import com.hotelops.paymentssim.webhook.PspWebhookEmitter;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PSP-004 — refund request side.
 *
 * <p>1C-a: non-transactional sequencer — persists the PENDING refund child (tx1), then
 * delegates settlement + the async REFUND webhook to {@link PspWebhookEmitter}, keyed on the
 * minted {@code refundMerchantReference}. The webhook fires off-thread; the {@code 202}
 * returns immediately.
 */
@RestController
@RequestMapping("/v1/payments/{pspReference}/refunds")
public class RefundController {

    private final PspRefundService service;
    private final PspWebhookEmitter emitter;
    private final PspApiKeyGate apiKeyGate;

    public RefundController(PspRefundService service, PspWebhookEmitter emitter,
                            PspApiKeyGate apiKeyGate) {
        this.service = service;
        this.emitter = emitter;
        this.apiKeyGate = apiKeyGate;
    }

    @PostMapping
    public ResponseEntity<RefundAckResponse> requestRefund(
            @RequestHeader(value = PspApiKeyGate.HEADER_NAME, required = false) String apiKey,
            @PathVariable String pspReference,
            @Valid @RequestBody RefundRequest request) {
        apiKeyGate.assertPresent(apiKey);
        RefundAckResponse ack = service.requestRefund(pspReference, request);  // tx1 — PENDING child
        emitter.emitRefund(ack.refundMerchantReference());                     // tx2 + async REFUND webhook
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ack);
    }
}
