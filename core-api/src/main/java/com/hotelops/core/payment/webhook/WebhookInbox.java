package com.hotelops.core.payment.webhook;

import com.hotelops.core.common.enums.PspEventCode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * SCH-070, SCH-071 — idempotent inbound PSP webhook log.
 *
 * core-api matches each inbound event to a payment by {@code merchantReference},
 * stamps the returned {@code pspReference} on the payment, and deduplicates by
 * {@code idempotencyKey} (pspReference-derived, UNIQUE — SCH-071).
 *
 * {@code rawPayload} is stored verbatim for audit.
 */
@Entity
@Table(name = "webhook_inbox")
@Getter
@Setter
@NoArgsConstructor
public class WebhookInbox {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    /**
     * SCH-071 — dedupe key derived from pspReference; UNIQUE constraint prevents
     * double-processing if the PSP retries the webhook delivery.
     */
    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "event_code", nullable = false)
    @Enumerated(EnumType.STRING)
    private PspEventCode eventCode;

    /** The merchantReference from the inbound event; used to look up the payment. */
    @Column(name = "merchant_reference", nullable = false)
    private String merchantReference;

    @Column(name = "psp_reference")
    private String pspReference;

    /** Full raw payload stored verbatim for auditability. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> rawPayload;

    @Column(name = "received_at", nullable = false, updatable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;

    @PrePersist
    void onCreate() {
        receivedAt = OffsetDateTime.now();
    }
}
