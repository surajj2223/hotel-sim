package com.hotelops.core.ledger;

import com.hotelops.core.booking.Booking;
import com.hotelops.core.booking.BookingLine;
import com.hotelops.core.common.enums.PostingType;
import com.hotelops.core.common.enums.Vertical;
import com.hotelops.core.payment.Payment;
import com.hotelops.core.payment.Refund;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * SCH-050, SCH-051 — ledger posting.
 *
 * Authorisation is NOT a posting (INV-006).
 * Only CAPTURE and REFUND events produce postings:
 * - CAPTURE  → {@link PostingType#REVENUE}          (positive amount, SCH-051)
 * - REFUND   → {@link PostingType#REFUND_REVERSAL}  (negative amount, SCH-051)
 *
 * Revenue is attributable by {@code vertical}; PSP trace-through via
 * {@code pspReference} / {@code merchantReference}.
 *
 * Postings are produced by {@link LedgerService} which consumes {@code outbox_event}
 * rows asynchronously and idempotently.
 */
@Entity
@Table(name = "ledger_posting")
@Getter
@Setter
@NoArgsConstructor
public class LedgerPosting {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "posting_type", nullable = false)
    @Enumerated(EnumType.STRING)
    private PostingType postingType;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    /** Nullable: some postings are folio-level rather than line-level. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_line_id")
    private BookingLine bookingLine;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id")
    private Payment payment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "refund_id")
    private Refund refund;

    /** Revenue is reported by vertical. */
    @Column(name = "vertical", nullable = false)
    @Enumerated(EnumType.STRING)
    private Vertical vertical;

    /**
     * SCH-051 — signed amount:
     * REVENUE &gt;= 0, REFUND_REVERSAL &lt;= 0.  DB CHECK constraint enforces this.
     */
    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency = "GBP";

    /** PSP trace-through to the underlying PSP transaction. */
    @Column(name = "psp_reference")
    private String pspReference;

    @Column(name = "merchant_reference")
    private String merchantReference;

    @Column(name = "narration")
    private String narration;

    @Column(name = "posted_at", nullable = false, updatable = false)
    private OffsetDateTime postedAt;

    @PrePersist
    void onCreate() {
        postedAt = OffsetDateTime.now();
    }
}
