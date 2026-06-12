package com.hotelops.paymentssim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * PSP-009 — payments-sim's reference ledger row. One row per merchantReference; UNIQUE
 * on (merchant_reference, payment_link_id, psp_reference).
 *
 * Stores-never-mints (WHK-001): {@code shopper_reference}, {@code merchant_reference}
 * are echoed verbatim from core-api. Mints {@code payment_link_id} at PSP-001 and
 * {@code psp_reference} at AUTHORISATION (PSP-013, lands in 1C).
 */
@Entity
@Table(name = "psp_payment")
@Getter
@Setter
@NoArgsConstructor
public class PspPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "merchant_reference", nullable = false, unique = true)
    private String merchantReference;

    @Column(name = "shopper_reference", nullable = false)
    private String shopperReference;

    @Column(name = "payment_link_id", nullable = false, unique = true)
    private String paymentLinkId;

    @Column(name = "psp_reference", unique = true)
    private String pspReference;

    @Column(name = "amount_requested", nullable = false)
    private long amountRequested;

    @Column(name = "amount_authorised", nullable = false)
    private long amountAuthorised = 0L;

    @Column(name = "amount_captured", nullable = false)
    private long amountCaptured = 0L;

    @Column(name = "amount_refunded", nullable = false)
    private long amountRefunded = 0L;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PspPaymentStatus status = PspPaymentStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "capture_mode", nullable = false)
    private CaptureMode captureMode;

    @Column(name = "callback_url", nullable = false)
    private String callbackUrl;

    /**
     * Capture intent recorded by PSP-002 (1B). Non-null = a CAPTURE webhook is queued
     * for this amount; 1C's dispatcher reads + clears this when it emits. Used by
     * PSP-002's single-capture check (INV-005) and PSP-003's cancel-not-permitted check.
     */
    @Column(name = "pending_capture_amount")
    private Long pendingCaptureAmount;

    /**
     * Cancellation intent recorded by PSP-003 (1B). true = a CANCELLATION webhook is
     * queued; 1C's dispatcher reads + clears this when it emits.
     */
    @Column(name = "cancellation_pending", nullable = false)
    private boolean cancellationPending = false;

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
