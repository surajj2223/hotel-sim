package com.hotelops.paymentssim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * PSP-010 — refund row. {@code original_reference} FKs to the parent's
 * {@code psp_payment.psp_reference}; {@code psp_reference} is a fresh PSP-minted value
 * distinct from the parent's. PENDING until 1C's dispatcher settles it via the REFUND
 * webhook.
 */
@Entity
@Table(name = "psp_refund")
@Getter
@Setter
@NoArgsConstructor
public class PspRefund {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "refund_merchant_reference", nullable = false, unique = true)
    private String refundMerchantReference;

    @Column(name = "psp_reference", nullable = false, unique = true)
    private String pspReference;

    @Column(name = "original_reference", nullable = false)
    private String originalReference;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PspRefundStatus status = PspRefundStatus.PENDING;

    @Column(name = "reason")
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
