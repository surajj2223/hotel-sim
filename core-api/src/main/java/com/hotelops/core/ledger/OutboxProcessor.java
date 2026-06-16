package com.hotelops.core.ledger;

import com.hotelops.core.common.enums.OutboxStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
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
 *
 * Flag-2 fix (SCH-061): a second pass reclaims rows stranded in PROCESSING by a crash
 * between the claim commit and the handler commit. See {@link #reclaimStaleProcessing()}.
 */
@Component
public class OutboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(OutboxProcessor.class);

    private final OutboxEventRepository outboxRepository;
    private final OutboxEventHandler eventHandler;

    /**
     * How long a row may sit in PROCESSING before the reclaim pass re-dispatches it. Must be
     * comfortably larger than the longest expected handler runtime so a merely-slow in-flight
     * handler is never reclaimed mid-flight (Trap B). Default 2 minutes; override via
     * {@code outbox.reclaim-after} (ISO-8601 duration, e.g. {@code PT2M}).
     */
    private final Duration reclaimAfter;

    @Autowired
    public OutboxProcessor(OutboxEventRepository outboxRepository,
                           OutboxEventHandler eventHandler,
                           @Value("${outbox.reclaim-after:PT2M}") Duration reclaimAfter) {
        this.outboxRepository = outboxRepository;
        this.eventHandler = eventHandler;
        this.reclaimAfter = reclaimAfter;
    }

    /** Test convenience: default 2-minute reclaim window. */
    public OutboxProcessor(OutboxEventRepository outboxRepository,
                           OutboxEventHandler eventHandler) {
        this(outboxRepository, eventHandler, Duration.ofMinutes(2));
    }

    @Scheduled(fixedDelay = 5000)
    public void processPending() {
        // Pass 1 — drain PENDING (unchanged).
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

        // Pass 2 — reclaim rows stranded in PROCESSING (Flag-2 crash recovery).
        reclaimStaleProcessing();
    }

    /**
     * SCH-061 — reclaim outbox rows stranded in PROCESSING.
     *
     * A row claimed PENDING→PROCESSING whose {@link OutboxEventHandler#processInTransaction}
     * never commits (JVM crash between the claim commit and the handler commit) is invisible
     * to the PENDING poll, so its ledger posting never lands. This pass finds rows that have
     * been PROCESSING longer than {@link #reclaimAfter} and re-dispatches them through the
     * SAME idempotent handler bean.
     *
     * Safety:
     * <ul>
     *   <li><b>Re-claim gate (Trap A/B, concurrency):</b> {@code reclaimStale} is a conditional
     *       UPDATE that re-stamps {@code claimed_at} only if the row is still PROCESSING and
     *       older than the cutoff. Only the tick/instance that wins (returns 1) re-dispatches;
     *       losers skip. Never a blind PROCESSING→PENDING requeue.</li>
     *   <li><b>Proxy (Trap C):</b> re-dispatch goes through the separate {@code eventHandler}
     *       bean, so the @Transactional boundary is real — exactly like the PENDING path.</li>
     *   <li><b>Idempotency backstop (option b):</b> if the posting already landed in the
     *       crashed run, the V2 unique indexes (uq_posting_capture_line / uq_posting_refund_line)
     *       reject the duplicate INSERT as a {@link DataIntegrityViolationException}. That is
     *       success, not failure: the row is advanced to PROCESSED rather than marked FAILED.</li>
     * </ul>
     */
    private void reclaimStaleProcessing() {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(reclaimAfter);
        List<OutboxEvent> stale = outboxRepository
                .findByStatusAndClaimedAtBeforeOrderByCreatedAtAsc(OutboxStatus.PROCESSING, cutoff);
        for (OutboxEvent event : stale) {
            int reclaimed = outboxRepository.reclaimStale(
                    event.getId(), OutboxStatus.PROCESSING, cutoff);
            if (reclaimed == 0) {
                continue; // another tick/instance won the re-claim
            }
            log.warn("Reclaiming stale PROCESSING outbox event {} (claimed before {})",
                    event.getId(), cutoff);
            try {
                eventHandler.processInTransaction(event.getId());
            } catch (DataIntegrityViolationException alreadyPosted) {
                // option (b): the ledger write already landed in the crashed run and the
                // unique index rejected the replay. Treat as done, not failed.
                log.info("Reclaimed outbox event {} was already posted (idempotency backstop); "
                        + "marking PROCESSED", event.getId());
                markQuietly(event.getId(), OutboxStatus.PROCESSED);
            } catch (Exception ex) {
                log.error("Reclaimed outbox event {} failed; marking FAILED: {}",
                        event.getId(), ex.getMessage(), ex);
                markQuietly(event.getId(), OutboxStatus.FAILED);
            }
        }
    }

    private void markQuietly(java.util.UUID eventId, OutboxStatus status) {
        try {
            outboxRepository.updateStatus(eventId, status);
        } catch (Exception markEx) {
            log.error("Could not mark event {} as {}: {}", eventId, status, markEx.getMessage(), markEx);
        }
    }
}
