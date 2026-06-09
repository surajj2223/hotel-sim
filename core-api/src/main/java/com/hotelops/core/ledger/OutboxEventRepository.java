package com.hotelops.core.ledger;

import com.hotelops.core.common.enums.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** SCH-060 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /** Poll PENDING events ordered by creation time (oldest first). */
    List<OutboxEvent> findByStatusOrderByCreatedAtAsc(OutboxStatus status);

    @Modifying
    @Query("UPDATE OutboxEvent e SET e.status = :newStatus, e.attempts = e.attempts + 1 WHERE e.id = :id")
    int updateStatus(@Param("id") UUID id, @Param("newStatus") OutboxStatus newStatus);
}
