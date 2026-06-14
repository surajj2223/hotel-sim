package com.hotelops.core.payment;

import com.hotelops.core.booking.BookingLine;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * WHK-016 — scoped payment→line coverage (Stage 4 Slice 1).
 *
 * A {@link Payment} MAY carry a set of {@code PaymentLine} rows declaring exactly which
 * booking lines it settles, and for how much each. This is a many-to-many association with
 * a per-line amount — deliberately NOT a payment→line foreign key, so it supports
 * "one card, many lines" and "one line, many cards" (split tender) simultaneously.
 *
 * When a payment has coverage rows, {@link com.hotelops.core.ledger.LedgerService} allocates
 * the captured/refunded amount across exactly these lines (scaled to the event amount).
 * When a payment has no coverage rows, the WHK-012 fill-by-line-order fallback is used,
 * unchanged. See {@code KNOWN_DESIGN_DECISION_PAYMENT_LINE_SCOPING.md}.
 *
 * Amounts are minor units (BIGINT), never float.
 */
@Entity
@Table(name = "payment_line")
@Getter
@Setter
@NoArgsConstructor
public class PaymentLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_line_id", nullable = false)
    private BookingLine bookingLine;

    /** The amount of this payment attributed to this booking line (minor units, > 0). */
    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency = "GBP";

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
