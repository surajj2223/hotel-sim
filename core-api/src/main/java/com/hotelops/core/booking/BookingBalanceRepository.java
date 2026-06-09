package com.hotelops.core.booking;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** SCH-021 — read-only repository for the booking_balance view. */
public interface BookingBalanceRepository extends JpaRepository<BookingBalance, UUID> {

    Optional<BookingBalance> findByBookingId(UUID bookingId);
}
