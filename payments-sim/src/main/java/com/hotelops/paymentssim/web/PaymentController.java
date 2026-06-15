package com.hotelops.paymentssim.web;

import com.hotelops.paymentssim.common.auth.PspApiKeyGate;
import com.hotelops.paymentssim.service.PspPaymentService;
import com.hotelops.paymentssim.web.dto.CancellationAckResponse;
import com.hotelops.paymentssim.web.dto.CaptureAckResponse;
import com.hotelops.paymentssim.web.dto.CaptureRequest;
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
 * PSP-002 / PSP-003 — capture + cancellation request side.
 *
 * <p>1C-a: this always-on controller is the non-transactional sequencer. It records intent
 * (tx1 on {@link PspPaymentService}), then delegates settlement + the async webhook to
 * {@link PspWebhookEmitter} (tx2 + post-commit dispatch). The webhook fires off-thread, so
 * the {@code 202} returns immediately and the HTTP call never sits inside a DB transaction.
 */
@RestController
@RequestMapping("/v1/payments/{pspReference}")
public class PaymentController {

    private final PspPaymentService service;
    private final PspWebhookEmitter emitter;
    private final PspApiKeyGate apiKeyGate;

    public PaymentController(PspPaymentService service, PspWebhookEmitter emitter,
                             PspApiKeyGate apiKeyGate) {
        this.service = service;
        this.emitter = emitter;
        this.apiKeyGate = apiKeyGate;
    }

    @PostMapping("/captures")
    public ResponseEntity<CaptureAckResponse> requestCapture(
            @RequestHeader(value = PspApiKeyGate.HEADER_NAME, required = false) String apiKey,
            @PathVariable String pspReference,
            @Valid @RequestBody(required = false) CaptureRequest request) {
        apiKeyGate.assertPresent(apiKey);
        Long amount = request == null ? null : request.amount();
        var intent = service.requestCapture(pspReference, amount);   // tx1 — record intent (commits)
        emitter.emitCapture(pspReference);                           // tx2 + async CAPTURE webhook
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new CaptureAckResponse(
                        intent.pspReference(),
                        intent.merchantReference(),
                        intent.amount(),
                        CaptureAckResponse.PENDING_CAPTURE));
    }

    @PostMapping("/cancellations")
    public ResponseEntity<CancellationAckResponse> requestCancellation(
            @RequestHeader(value = PspApiKeyGate.HEADER_NAME, required = false) String apiKey,
            @PathVariable String pspReference) {
        apiKeyGate.assertPresent(apiKey);
        var intent = service.requestCancellation(pspReference);      // tx1 — record intent (commits)
        emitter.emitCancellation(pspReference);                      // tx2 + async CANCELLATION webhook
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new CancellationAckResponse(
                        intent.pspReference(),
                        intent.merchantReference(),
                        CancellationAckResponse.PENDING_CANCELLATION));
    }
}
