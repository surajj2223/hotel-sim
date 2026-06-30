package com.hotelops.core.payment;

import com.hotelops.core.common.enums.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SCH-030, SCH-031 */
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findByBookingId(UUID bookingId);

    /** SCH-031 — merchant_reference is the reconciliation anchor. */
    Optional<Payment> findByMerchantReference(String merchantReference);

    Optional<Payment> findByPspReference(String pspReference);

    List<Payment> findByStatus(PaymentStatus status);

    /** INV-004: sum of captured amounts for a booking (drives booking.amountPaid). */
    @Query("""
           SELECT COALESCE(SUM(p.amountCaptured), 0)
           FROM Payment p
           WHERE p.booking.id = :bookingId
           """)
    long sumCapturedForBooking(@Param("bookingId") UUID bookingId);

    /**
     * RX-004: the folio's LIVE authorised hold — sum of amount_authorised over the booking's
     * payments that are still AUTHORISED only. A captured (IMMEDIATE or MANUAL) payment's auth is
     * spent and no longer a live hold, so it is excluded. Derived on read in DtoMapper folio
     * assembly; never stored on the booking.
     */
    @Query("""
           SELECT COALESCE(SUM(p.amountAuthorised), 0)
           FROM Payment p
           WHERE p.booking.id = :bookingId
             AND p.status = com.hotelops.core.common.enums.PaymentStatus.AUTHORISED
           """)
    long sumAuthorisedForBooking(@Param("bookingId") UUID bookingId);
}
