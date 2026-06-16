package com.hotelops.core.ledger;

import com.hotelops.core.common.enums.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** SCH-060 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /** Poll PENDING events ordered by creation time (oldest first). */
    List<OutboxEvent> findByStatusOrderByCreatedAtAsc(OutboxStatus status);

    /**
     * SCH-061 — reclaim candidates: rows stuck in a given status (PROCESSING) whose claim
     * timestamp is older than the cutoff. Backed by the {@code idx_outbox_reclaim} partial
     * index. Rows with a NULL {@code claimed_at} (pre-V5) are excluded by the {@code <}
     * comparison, so they are never reclaimed.
     */
    List<OutboxEvent> findByStatusAndClaimedAtBeforeOrderByCreatedAtAsc(
            OutboxStatus status, OffsetDateTime cutoff);

    @Modifying
    @Transactional
    @Query("UPDATE OutboxEvent e SET e.status = :newStatus, e.attempts = e.attempts + 1 WHERE e.id = :id")
    int updateStatus(@Param("id") UUID id, @Param("newStatus") OutboxStatus newStatus);

    /**
     * WHK-013 / GAP-2: conditional claim — flips PENDING → PROCESSING only if the row
     * is still PENDING. Returns 1 if this caller won the claim, 0 if already taken.
     * The single-row conditional UPDATE is atomic; no separate SELECT needed.
     *
     * SCH-061: the same UPDATE stamps {@code claimed_at = CURRENT_TIMESTAMP} so the
     * reclaim pass can tell how long a row has been PROCESSING. Done in JPQL (not a
     * Java-supplied param) to keep this method's signature stable.
     */
    @Modifying
    @Transactional
    @Query("UPDATE OutboxEvent e SET e.status = :to, e.attempts = e.attempts + 1, " +
           "e.claimedAt = CURRENT_TIMESTAMP WHERE e.id = :id AND e.status = :from")
    int claimEvent(@Param("id") UUID id,
                   @Param("from") OutboxStatus from,
                   @Param("to") OutboxStatus to);

    /**
     * SCH-061 — conditional RE-claim of a stale PROCESSING row (Flag-2 crash recovery).
     *
     * Re-stamps {@code claimed_at} only if the row is still in {@code status} (PROCESSING)
     * AND was claimed before {@code cutoff}. Returns 1 if this caller won the re-claim, 0
     * otherwise. This is the concurrency gate (Trap A/B): only the winning tick/instance
     * re-dispatches, and re-stamping stops a genuinely-stuck row being re-grabbed every
     * tick. Mirrors {@link #claimEvent} — it never flips PROCESSING back to PENDING.
     */
    @Modifying
    @Transactional
    @Query("UPDATE OutboxEvent e SET e.attempts = e.attempts + 1, e.claimedAt = CURRENT_TIMESTAMP " +
           "WHERE e.id = :id AND e.status = :status AND e.claimedAt < :cutoff")
    int reclaimStale(@Param("id") UUID id,
                     @Param("status") OutboxStatus status,
                     @Param("cutoff") OffsetDateTime cutoff);
}
