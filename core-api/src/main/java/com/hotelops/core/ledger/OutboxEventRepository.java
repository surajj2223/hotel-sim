package com.hotelops.core.ledger;

import com.hotelops.core.common.enums.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** SCH-060 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /** Poll PENDING events ordered by creation time (oldest first). */
    List<OutboxEvent> findByStatusOrderByCreatedAtAsc(OutboxStatus status);

    @Modifying
    @Transactional
    @Query("UPDATE OutboxEvent e SET e.status = :newStatus, e.attempts = e.attempts + 1 WHERE e.id = :id")
    int updateStatus(@Param("id") UUID id, @Param("newStatus") OutboxStatus newStatus);

    /**
     * WHK-013 / GAP-2: conditional claim — flips PENDING → PROCESSING only if the row
     * is still PENDING. Returns 1 if this caller won the claim, 0 if already taken.
     * The single-row conditional UPDATE is atomic; no separate SELECT needed.
     */
    @Modifying
    @Transactional
    @Query("UPDATE OutboxEvent e SET e.status = :to, e.attempts = e.attempts + 1 " +
           "WHERE e.id = :id AND e.status = :from")
    int claimEvent(@Param("id") UUID id,
                   @Param("from") OutboxStatus from,
                   @Param("to") OutboxStatus to);
}
