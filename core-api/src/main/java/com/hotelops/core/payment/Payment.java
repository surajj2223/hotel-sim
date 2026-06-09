package com.hotelops.core.payment;

import com.hotelops.core.booking.Booking;
import com.hotelops.core.common.enums.CaptureMode;
import com.hotelops.core.common.enums.PaymentStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SCH-030, SCH-031 — payment.  One booking may have many payments.
 *
 * Reference taxonomy (ENM-010):
 * - {@code shopperReference}: copied from customer at creation; never changes.
 * - {@code merchantReference}: our ref per attempt (SCH-031, reconciliation anchor; UNIQUE).
 * - {@code pspReference}: minted by payments-sim on auth; stamped here on AUTHORISATION webhook.
 * - {@code paymentLinkId}: minted by payments-sim on link creation.
 *
 * Amounts (all minor units, never float):
 * - SCH-032: amount_captured &lt;= amount_authorised  (DB CHECK constraint).
 * - SCH-033: amount_refunded &lt;= amount_captured    (DB CHECK constraint).
 * - INV-005: single capture per auth — enforced by {@link PaymentService}.
 */
@Entity
@Table(name = "payment")
@Getter
@Setter
@NoArgsConstructor
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    /** Copied from customer.shopperReference at payment creation (continuity). */
    @Column(name = "shopper_reference", nullable = false)
    private String shopperReference;

    /** SCH-031 — our ref per payment attempt; the reconciliation anchor. */
    @Column(name = "merchant_reference", nullable = false, unique = true)
    private String merchantReference;

    /** Minted by payments-sim on AUTHORISATION; null until then. */
    @Column(name = "psp_reference", unique = true)
    private String pspReference;

    /** Minted by payments-sim on payment link creation; null until then. */
    @Column(name = "payment_link_id", unique = true)
    private String paymentLinkId;

    @Column(name = "capture_mode", nullable = false)
    @Enumerated(EnumType.STRING)
    private CaptureMode captureMode;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency = "GBP";

    @Column(name = "amount_requested", nullable = false)
    private long amountRequested;

    @Column(name = "amount_authorised", nullable = false)
    private long amountAuthorised = 0L;

    /** SCH-032 constraint: captured &lt;= authorised.  Maintained by PaymentService. */
    @Column(name = "amount_captured", nullable = false)
    private long amountCaptured = 0L;

    /** SCH-033 constraint: refunded &lt;= captured.  Maintained by PaymentService. */
    @Column(name = "amount_refunded", nullable = false)
    private long amountRefunded = 0L;

    @Column(name = "auth_expires_at")
    private OffsetDateTime authExpiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @OneToMany(mappedBy = "payment", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Refund> refunds = new ArrayList<>();

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
