package com.hotelops.core.booking;

import com.hotelops.core.common.enums.BookingStatus;
import com.hotelops.core.customer.Customer;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SCH-020 — booking (folio).
 *
 * A booking groups one or more {@link BookingLine}s (potentially spanning verticals)
 * for a single customer.  The three amount columns are maintained by core-api on
 * capture/refund events (INV-004); clients never write them directly.
 *
 * "Paid" == (customerOwes == 0), not a boolean (RX-003).  customerOwes / netRevenue are
 * exposed via the {@code booking_balance} view (SCH-021); see {@link BookingBalance} for the
 * JPA projection.
 *
 * Amounts are always in integer minor units (pence).
 */
@Entity
@Table(name = "booking")
@Getter
@Setter
@NoArgsConstructor
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private BookingStatus status = BookingStatus.PENDING;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency = "GBP";

    /** Sum of active line_amounts.  Maintained by INV-004. */
    @Column(name = "total_amount", nullable = false)
    private long totalAmount = 0L;

    /** Sum of amount_captured across all payments for this booking.  INV-004. */
    @Column(name = "amount_paid", nullable = false)
    private long amountPaid = 0L;

    /**
     * D3 (Stage 4) — live roll-up of amount_authorised across all payments for this booking.
     * The folio's "secured" figure. Maintained by INV-004 alongside the others; visible only,
     * no enforcement (no checkout-blocking, no incremental-auth).
     */
    @Column(name = "amount_authorised", nullable = false)
    private long amountAuthorised = 0L;

    /** Sum of settled refund amounts for this booking.  INV-004. */
    @Column(name = "amount_refunded", nullable = false)
    private long amountRefunded = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true,
               fetch = FetchType.LAZY)
    private List<BookingLine> lines = new ArrayList<>();

    /** INV-004 (RX-003): what the customer still owes — max(0, total - paid).
     *  Refunds never increase this (a refund cannot make a customer owe more); the clamp
     *  keeps an over-capture from rendering as a negative receivable. Settlement predicate. */
    public long getCustomerOwes() {
        return Math.max(0L, totalAmount - amountPaid);
    }

    /** INV-004 (RX-003): net revenue the hotel retained — paid - refunded. Finance read only. */
    public long getNetRevenue() {
        return amountPaid - amountRefunded;
    }

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
