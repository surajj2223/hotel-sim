package com.hotelops.core.ledger;

import com.hotelops.core.common.enums.OutboxStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * SCH-060 — outbox processor: polls PENDING outbox events and drives
 * {@link LedgerService} to create the appropriate ledger postings.
 *
 * Design: the processor is the ONLY consumer; it runs in a loop and marks events
 * PROCESSED or FAILED.  Idempotency is guaranteed because each event produces at most
 * one posting (processing checks PENDING before dispatching).
 */
@Component
public class OutboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(OutboxProcessor.class);

    private final OutboxEventRepository outboxRepository;
    private final LedgerService ledgerService;

    public OutboxProcessor(OutboxEventRepository outboxRepository, LedgerService ledgerService) {
        this.outboxRepository = outboxRepository;
        this.ledgerService = ledgerService;
    }

    @Scheduled(fixedDelay = 5000)
    public void processPending() {
        List<OutboxEvent> pending = outboxRepository
                .findByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);
        for (OutboxEvent event : pending) {
            process(event);
        }
    }

    @Transactional
    protected void process(OutboxEvent event) {
        try {
            dispatch(event);
            event.setStatus(OutboxStatus.PROCESSED);
            event.setProcessedAt(OffsetDateTime.now());
        } catch (Exception ex) {
            log.error("Failed to process outbox event {}: {}", event.getId(), ex.getMessage(), ex);
            event.setStatus(OutboxStatus.FAILED);
            event.setAttempts(event.getAttempts() + 1);
        }
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
