package com.hotelops.core.ledger;

import com.hotelops.core.common.enums.OutboxStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SCH-060 — outbox scheduler: polls PENDING events and drives the ledger.
 *
 * GAP-2 fix (WHK-013):
 * (a) Each event is claimed with a conditional PENDING→PROCESSING update before
 *     dispatch; only the tick that wins the update (returns 1) proceeds.
 * (b) The transactional ledger-write is delegated to {@link OutboxEventHandler}
 *     (a separate injected bean) so Spring's proxy applies @Transactional correctly.
 *     The old design had @Transactional on a protected self-invoked method in this
 *     same class, which bypassed the proxy entirely.
 */
@Component
public class OutboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(OutboxProcessor.class);

    private final OutboxEventRepository outboxRepository;
    private final OutboxEventHandler eventHandler;

    public OutboxProcessor(OutboxEventRepository outboxRepository,
                           OutboxEventHandler eventHandler) {
        this.outboxRepository = outboxRepository;
        this.eventHandler = eventHandler;
    }

    @Scheduled(fixedDelay = 5000)
    public void processPending() {
        List<OutboxEvent> pending = outboxRepository
                .findByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);
        for (OutboxEvent event : pending) {
            int claimed = outboxRepository.claimEvent(
                    event.getId(), OutboxStatus.PENDING, OutboxStatus.PROCESSING);
            if (claimed == 0) {
                continue; // another tick already claimed this event
            }
            try {
                // Cross-bean call → Spring proxy applies @Transactional on processInTransaction
                eventHandler.processInTransaction(event.getId());
            } catch (Exception ex) {
                log.error("Outbox event {} failed; marking FAILED: {}", event.getId(), ex.getMessage(), ex);
                try {
                    outboxRepository.updateStatus(event.getId(), OutboxStatus.FAILED);
                } catch (Exception markEx) {
                    log.error("Could not mark event {} as FAILED: {}", event.getId(), markEx.getMessage(), markEx);
                }
            }
        }
    }
}
