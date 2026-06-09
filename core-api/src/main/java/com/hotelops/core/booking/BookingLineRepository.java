package com.hotelops.core.booking;

import com.hotelops.core.common.enums.BookingLineStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** SCH-022 */
public interface BookingLineRepository extends JpaRepository<BookingLine, UUID> {

    List<BookingLine> findByBookingId(UUID bookingId);

    List<BookingLine> findByBookingIdAndStatus(UUID bookingId, BookingLineStatus status);

    /**
     * Lock-read: count active lines overlapping a time window for a product.
     * Used by INV-003 write-time revalidation in BookingService.
     * Uses PESSIMISTIC_READ to prevent concurrent availability races.
     */
    @Query(value = """
           SELECT COALESCE(SUM(bl.quantity), 0)
           FROM booking_line bl
           WHERE bl.product_id = :productId
             AND bl.status = 'ACTIVE'
             AND bl.starts_at < :endsAt
             AND bl.ends_at   > :startsAt
           """, nativeQuery = true)
    long lockedCountCommitted(
            @Param("productId") UUID productId,
            @Param("startsAt") OffsetDateTime startsAt,
            @Param("endsAt") OffsetDateTime endsAt);
}
