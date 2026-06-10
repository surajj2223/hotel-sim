package com.hotelops.core.booking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.Synchronize;

import java.util.UUID;

/**
 * SCH-021 — read-only projection of the {@code booking_balance} view.
 *
 * Balance = total_amount - amount_paid + amount_refunded.
 * "Paid" is defined as balance == 0; there is no boolean paid flag (see project brief §6).
 *
 * This entity is immutable — only SELECT queries are valid.
 */
@Entity
@Table(name = "booking_balance")
@Immutable
// The view reads from booking; declare the dependency so Hibernate flushes pending
// booking changes before querying this view (otherwise same-transaction reads miss them).
@Synchronize("booking")
@Getter
public class BookingBalance {

    @Id
    @Column(name = "booking_id")
    private UUID bookingId;

    @Column(name = "total_amount")
    private long totalAmount;

    @Column(name = "amount_paid")
    private long amountPaid;

    @Column(name = "amount_refunded")
    private long amountRefunded;

    /** Derived: total_amount - amount_paid + amount_refunded. */
    @Column(name = "balance")
    private long balance;

    /** Convenience: a booking is fully paid when balance == 0. */
    public boolean isPaid() {
        return balance == 0L;
    }
}
