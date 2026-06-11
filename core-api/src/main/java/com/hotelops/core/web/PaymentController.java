package com.hotelops.core.web;

import com.hotelops.core.common.auth.HumanAuthorizationGate;
import com.hotelops.core.payment.Payment;
import com.hotelops.core.payment.PaymentRepository;
import com.hotelops.core.payment.PaymentService;
import com.hotelops.core.payment.Refund;
import com.hotelops.core.payment.RefundRepository;
import com.hotelops.core.web.dto.CaptureRequest;
import com.hotelops.core.web.dto.PaymentResponse;
import com.hotelops.core.web.dto.RefundRequest;
import com.hotelops.core.web.dto.RefundResponse;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Payment endpoints rooted at a payment id — API-009 (getPayment, ungated),
 * API-010 (capturePayment), API-011 (cancelAuthorisation), API-012 (refundPayment).
 *
 * Per WHK-015 the three repercussive writes are asynchronous: they return {@code 202}
 * having only requested the action at the PSP. State transition + ledger postings land
 * on the matching inbound webhook ({@link WebhookController}). Operator gating uses
 * {@code X-Human-Auth} (INV-007); the webhook is signature-authenticated, not human-gated.
 */
@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final HumanAuthorizationGate humanAuth;
    private final DtoMapper mapper;

    public PaymentController(PaymentService paymentService,
                             PaymentRepository paymentRepository,
                             RefundRepository refundRepository,
                             HumanAuthorizationGate humanAuth,
                             DtoMapper mapper) {
        this.paymentService = paymentService;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.humanAuth = humanAuth;
        this.mapper = mapper;
    }

    /** API-009: a single payment with amounts, references, and refunds. */
    @GetMapping("/{paymentId}")
    public PaymentResponse getPayment(@PathVariable UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new EntityNotFoundException("Payment not found: " + paymentId));
        return toResponse(payment);
    }

    /**
     * API-010: request capture of an authorised payment (full or partial).
     * 202 Accepted — the {@code CAPTURE} webhook (WHK-007) is the authoritative settlement
     * signal that flips status to {@code CAPTURED} and drives per-line REVENUE postings.
     * Single capture only (INV-005); amount > authorised → 409 (SCH-032).
     */
    @PostMapping("/{paymentId}/capture")
    public ResponseEntity<PaymentResponse> capturePayment(
            @PathVariable UUID paymentId,
            @RequestHeader(value = HumanAuthorizationGate.HEADER_NAME, required = false) String humanAuthToken,
            @RequestBody(required = false) CaptureRequest request) {
        humanAuth.assertAuthorised(humanAuthToken, "capturePayment");
        Long amount = (request != null) ? request.amount() : null;
        Payment payment = paymentService.requestCapture(paymentId, amount);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(toResponse(payment));
    }

    /**
     * API-011: request cancellation of an uncaptured authorisation.
     * 202 Accepted — the {@code CANCELLATION} webhook (WHK-008) flips status to
     * {@code CANCELLED}. No ledger effect (INV-006). 409 if already captured.
     */
    @PostMapping("/{paymentId}/cancel")
    public ResponseEntity<PaymentResponse> cancelAuthorisation(
            @PathVariable UUID paymentId,
            @RequestHeader(value = HumanAuthorizationGate.HEADER_NAME, required = false) String humanAuthToken) {
        humanAuth.assertAuthorised(humanAuthToken, "cancelAuthorisation");
        Payment payment = paymentService.requestCancellation(paymentId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(toResponse(payment));
    }

    /**
     * API-012: request a refund (part or all) of a captured payment as a child Refund.
     * 202 Accepted — the {@code REFUND} webhook (WHK-009) settles the refund row and
     * drives per-line REFUND_REVERSAL postings. Amount > remaining capturable → 409 (SCH-033).
     */
    @PostMapping("/{paymentId}/refunds")
    public ResponseEntity<RefundResponse> refundPayment(
            @PathVariable UUID paymentId,
            @RequestHeader(value = HumanAuthorizationGate.HEADER_NAME, required = false) String humanAuthToken,
            @Valid @RequestBody RefundRequest request) {
        humanAuth.assertAuthorised(humanAuthToken, "refundPayment");
        Refund refund = paymentService.createRefund(paymentId, request.amount(),
                PaymentService.mintMerchantReference(), request.reason());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(mapper.toRefundResponse(refund));
    }

    private PaymentResponse toResponse(Payment payment) {
        return mapper.toPaymentResponse(
                payment,
                refundRepository.findByPaymentId(payment.getId()));
    }
}
