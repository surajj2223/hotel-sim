package com.hotelops.core.ledger;

import com.hotelops.core.common.enums.OutboxStatus;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * WHK-013 / GAP-2: the transactional ledger-write unit on a separate bean.
 *
 * Spring's proxy wraps this bean; {@link #processInTransaction(UUID)} is called from
 * {@link OutboxProcessor} (a different bean), so the proxy intercepts the call and
 * the @Transactional boundary is real.  This is the fix for the GAP-2 self-invocation
 * trap — the old code put @Transactional on a protected method called within the same
 * bean, which bypassed the proxy entirely.
 */
@Component
public class OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventHandler.class);

    private final OutboxEventRepository outboxRepository;
    private final LedgerService ledgerService;

    public OutboxEventHandler(OutboxEventRepository outboxRepository,
                               LedgerService ledgerService) {
        this.outboxRepository = outboxRepository;
        this.ledgerService = ledgerService;
    }

    /**
     * WHK-013: ledger write + PROCESSED status flip in a single proxied transaction.
     *
     * Must be called from a DIFFERENT bean (OutboxProcessor) so Spring's proxy applies
     * the @Transactional boundary. Idempotency backstop: the DB unique indexes on
     * ledger_posting prevent duplicate rows if this method is replayed for the same event.
     */
    @Transactional
    public void processInTransaction(UUID eventId) {
        OutboxEvent event = outboxRepository.findById(eventId)
                .orElseThrow(() -> new EntityNotFoundException("Outbox event not found: " + eventId));

        dispatch(event);

        event.setStatus(OutboxStatus.PROCESSED);
        event.setProcessedAt(OffsetDateTime.now());
        outboxRepository.save(event);
    }

    private void dispatch(OutboxEvent event) {
        switch (event.getEventType()) {
            case "PAYMENT_CAPTURED" -> {
                UUID paymentId = UUID.fromString((String) event.getPayload().get("paymentId"));
                ledgerService.postCapture(paymentId);
            }
            case "REFUND_SETTLED" -> {
                UUID refundId = UUID.fromString((String) event.getPayload().get("refundId"));
                ledgerService.postRefund(refundId);
            }
            default -> log.warn("Unknown outbox event type: {}", event.getEventType());
        }
    }
}
