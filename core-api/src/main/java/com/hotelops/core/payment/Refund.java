package com.hotelops.core.payment;

import com.hotelops.core.common.enums.RefundStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * SCH-040 — refund.  One-to-many child of {@link Payment}.
 *
 * Each refund has its own {@code pspReference} (minted by payments-sim for the refund
 * event) and {@code originalReference} which chains back to the parent payment's
 * {@code pspReference} (the parent/child PSP reference chain).
 *
 * Partial refunds are supported; cannot refund more than captured (SCH-033 / DB CHECK).
 */
@Entity
@Table(name = "refund")
@Getter
@Setter
@NoArgsConstructor
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    /** Minor units.  DB CHECK: amount > 0. */
    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency = "GBP";

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private RefundStatus status = RefundStatus.PENDING;

    /** Our reference for this refund attempt (UNIQUE). */
    @Column(name = "merchant_reference", nullable = false, unique = true)
    private String merchantReference;

    /** Minted by payments-sim for the refund; null until REFUND webhook received. */
    @Column(name = "psp_reference", unique = true)
    private String pspReference;

    /** The parent payment's pspReference — PSP parent/child chain. */
    @Column(name = "original_reference", nullable = false)
    private String originalReference;

    @Column(name = "reason")
    private String reason;

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
