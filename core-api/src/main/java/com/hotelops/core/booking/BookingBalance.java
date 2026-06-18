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
 * RX-003: the overloaded {@code balance} column was split into two separately-derived
 * numbers — {@code customer_owes = max(0, total_amount - amount_paid)} (settlement) and
 * {@code net_revenue = amount_paid - amount_refunded} (finance read).
 * "Paid" is defined as customerOwes == 0; there is no boolean paid flag (see project brief §6).
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

    /** Derived (RX-003): max(0, total_amount - amount_paid) — what the customer still owes. */
    @Column(name = "customer_owes")
    private long customerOwes;

    /** Derived (RX-003): amount_paid - amount_refunded — net revenue the hotel retained. */
    @Column(name = "net_revenue")
    private long netRevenue;

    /** Convenience: a booking is fully settled when customerOwes == 0. */
    public boolean isPaid() {
        return customerOwes == 0L;
    }
}
