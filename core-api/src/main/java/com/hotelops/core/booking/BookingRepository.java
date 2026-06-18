package com.hotelops.core.booking;

import com.hotelops.core.common.enums.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** SCH-020 */
public interface BookingRepository extends JpaRepository<Booking, UUID> {

    List<Booking> findByCustomerId(UUID customerId);

    List<Booking> findByStatus(BookingStatus status);

    /** INV-004 (RX-003 D2): listUnpaidBookings — bookings where customerOwes > 0, i.e.
     *  (total - paid) > 0. The old predicate added amount_refunded, which wrongly re-listed a
     *  fully-refunded booking as unpaid (the RX-003 defect; this query was outside §4's stated
     *  blast radius — corrected here to the settlement predicate). */
    @Query("""
           SELECT b FROM Booking b
           WHERE (b.totalAmount - b.amountPaid) > 0
           """)
    List<Booking> findUnpaid();

    /** INV-004: sum of active line_amounts for a booking (for roll-up). */
    @Query("""
           SELECT COALESCE(SUM(bl.lineAmount), 0)
           FROM BookingLine bl
           WHERE bl.booking.id = :bookingId
             AND bl.status = com.hotelops.core.common.enums.BookingLineStatus.ACTIVE
           """)
    long sumActiveLineAmounts(@Param("bookingId") UUID bookingId);
}
