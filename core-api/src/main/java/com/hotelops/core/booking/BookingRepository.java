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

    /** INV-004: listUnpaidBookings — bookings where balance > 0. */
    @Query("""
           SELECT b FROM Booking b
           WHERE (b.totalAmount - b.amountPaid + b.amountRefunded) > 0
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
