package com.hotelops.core.payment;

import com.hotelops.core.common.enums.RefundStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SCH-040 */
public interface RefundRepository extends JpaRepository<Refund, UUID> {

    List<Refund> findByPaymentId(UUID paymentId);

    Optional<Refund> findByMerchantReference(String merchantReference);

    List<Refund> findByStatus(RefundStatus status);

    /** INV-004: sum of settled refund amounts for a booking. */
    @Query("""
           SELECT COALESCE(SUM(r.amount), 0)
           FROM Refund r
           WHERE r.payment.booking.id = :bookingId
             AND r.status = com.hotelops.core.common.enums.RefundStatus.REFUNDED
           """)
    long sumSettledRefundsForBooking(@Param("bookingId") UUID bookingId);
}
