package com.hotelops.core.booking;

import com.hotelops.core.common.enums.BookingLineStatus;
import com.hotelops.core.common.enums.Vertical;
import com.hotelops.core.product.Product;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * SCH-022 — booking line with FLAT PRICE SNAPSHOT.
 *
 * {@code unitPrice} and {@code lineAmount} are snapshots of the price at booking time;
 * they do NOT change if the product's base_price changes later.
 * {@code lineAmount = unitPrice * quantity} — this is enforced by a DB CHECK constraint.
 *
 * The partial index on (product_id, starts_at, ends_at) WHERE status='ACTIVE' is used
 * by availability queries to count committed demand efficiently.
 *
 * Amounts in minor units (pence).
 */
@Entity
@Table(name = "booking_line")
@Getter
@Setter
@NoArgsConstructor
public class BookingLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** Denormalised from product for query convenience; set at creation time. */
    @Column(name = "vertical", nullable = false)
    @Enumerated(EnumType.STRING)
    private Vertical vertical;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private BookingLineStatus status = BookingLineStatus.ACTIVE;

    @Column(name = "starts_at", nullable = false)
    private OffsetDateTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private OffsetDateTime endsAt;

    /** nights / covers / seats — must be > 0. */
    @Column(name = "quantity", nullable = false)
    private int quantity = 1;

    /** Snapshot of unit price at booking time (minor units). */
    @Column(name = "unit_price", nullable = false)
    private long unitPrice;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency = "GBP";

    /** lineAmount == unitPrice * quantity — enforced by DB CHECK constraint. */
    @Column(name = "line_amount", nullable = false)
    private long lineAmount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
