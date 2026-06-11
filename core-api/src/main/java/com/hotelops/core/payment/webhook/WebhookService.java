package com.hotelops.core.payment.webhook;

import com.hotelops.core.common.enums.PspEventCode;
import com.hotelops.core.payment.PaymentRepository;
import com.hotelops.core.payment.PaymentService;
import com.hotelops.core.payment.RefundRepository;
import com.hotelops.core.web.dto.PspWebhookEvent;
import com.hotelops.core.web.dto.WebhookAck;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Inbound PSP webhook orchestration — WAVE0_03 §4 processing order:
 *   1. persist webhook_inbox (idempotency_key UNIQUE; duplicate → ack 200) [WHK-005]
 *   2. resolve target (Payment by merchantReference, Refund by refundMerchantReference);
 *      unknown reference → ack, no mutation [WHK-004]
 *   3. apply the event-specific transition via PaymentService settle methods [WHK-006..011]
 *   4. stamp processed_at
 *
 * Signature verification is in the controller (WHK-014). This service is a separate bean
 * with public {@code @Transactional} methods — avoids the GAP-2 self-invocation trap.
 */
@Service
public class WebhookService {

    private final WebhookInboxRepository inboxRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final PaymentService paymentService;

    public WebhookService(WebhookInboxRepository inboxRepository,
                          PaymentRepository paymentRepository,
                          RefundRepository refundRepository,
                          PaymentService paymentService) {
        this.inboxRepository = inboxRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.paymentService = paymentService;
    }

    /**
     * Process one inbound PSP webhook event.
     * Returns {@code duplicate=true} when {@code idempotencyKey} already exists (WHK-005).
     * Returns ack with no mutation when the referenced target is unknown (WHK-004).
     */
    @Transactional
    public WebhookAck process(PspWebhookEvent event) {
        // Step 1: insert-first inbox row; duplicate key → already-processed, ack no-effect.
        WebhookInbox inbox;
        try {
            inbox = persistInbox(event);
        } catch (DataIntegrityViolationException duplicate) {
            return new WebhookAck(true, true);
        }

        // Step 2: resolve target. Unknown → ack, no mutation; inbox row remains for audit.
        boolean targetKnown = isTargetKnown(event);
        if (!targetKnown) {
            inbox.setProcessedAt(OffsetDateTime.now());
            inboxRepository.save(inbox);
            return new WebhookAck(true, false);
        }

        // Step 3: apply transition.
        dispatch(event);

        // Step 4: stamp processed_at.
        inbox.setProcessedAt(OffsetDateTime.now());
        inboxRepository.save(inbox);

        return new WebhookAck(true, false);
    }

    private WebhookInbox persistInbox(PspWebhookEvent event) {
        WebhookInbox inbox = new WebhookInbox();
        inbox.setIdempotencyKey(event.idempotencyKey());
        inbox.setEventCode(event.eventCode());
        inbox.setMerchantReference(event.merchantReference());
        inbox.setPspReference(event.pspReference());
        inbox.setRawPayload(rawPayload(event));
        return inboxRepository.saveAndFlush(inbox);
    }

    private boolean isTargetKnown(PspWebhookEvent event) {
        if (isRefundEvent(event.eventCode())) {
            String ref = event.refundMerchantReference();
            return ref != null && refundRepository.findByMerchantReference(ref).isPresent();
        }
        return paymentRepository.findByMerchantReference(event.merchantReference()).isPresent();
    }

    private void dispatch(PspWebhookEvent event) {
        switch (event.eventCode()) {
            case AUTHORISATION -> paymentService.recordAuthorisation(
                    event.merchantReference(),
                    event.pspReference(),
                    event.amount(),
                    event.authExpiresAt());
            case CAPTURE -> paymentService.settleCapture(
                    event.merchantReference(),
                    event.amount(),
                    event.pspReference());
            case CAPTURE_FAILED -> paymentService.settleCaptureFailure(
                    event.merchantReference(),
                    event.reason());
            case CANCELLATION -> paymentService.settleCancellation(
                    event.merchantReference());
            case REFUND -> paymentService.settleRefund(
                    event.refundMerchantReference(),
                    event.pspReference());
            case REFUND_FAILED -> paymentService.settleRefundFailure(
                    event.refundMerchantReference(),
                    event.reason());
            case AUTH_EXPIRY -> paymentService.settleAuthExpiry(
                    event.merchantReference());
        }
    }

    private static boolean isRefundEvent(PspEventCode code) {
        return code == PspEventCode.REFUND || code == PspEventCode.REFUND_FAILED;
    }

    private static Map<String, Object> rawPayload(PspWebhookEvent e) {
        Map<String, Object> p = new HashMap<>();
        p.put("eventId", e.eventId());
        p.put("eventCode", e.eventCode().name());
        p.put("idempotencyKey", e.idempotencyKey());
        p.put("merchantReference", e.merchantReference());
        p.put("pspReference", e.pspReference());
        p.put("amount", e.amount());
        p.put("currency", e.currency());
        p.put("occurredAt", e.occurredAt() != null ? e.occurredAt().toString() : null);
        p.put("success", e.success());
        p.put("authExpiresAt", e.authExpiresAt() != null ? e.authExpiresAt().toString() : null);
        p.put("originalReference", e.originalReference());
        p.put("refundMerchantReference", e.refundMerchantReference());
        p.put("reason", e.reason());
        return p;
    }
}
