package com.hotelops.core.ledger;

import com.hotelops.core.common.enums.PostingType;
import com.hotelops.core.common.enums.Vertical;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** SCH-050 */
public interface LedgerPostingRepository extends JpaRepository<LedgerPosting, UUID> {

    List<LedgerPosting> findByBookingId(UUID bookingId);

    List<LedgerPosting> findByVertical(Vertical vertical);

    /** Revenue query: sum by vertical in a time window (getRevenue capability). */
    @Query("""
           SELECT lp.vertical, SUM(lp.amount)
           FROM LedgerPosting lp
           WHERE lp.postingType = :type
             AND lp.postedAt >= :from
             AND lp.postedAt < :to
           GROUP BY lp.vertical
           """)
    List<Object[]> sumByVertical(
            @Param("type") PostingType type,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);
}
