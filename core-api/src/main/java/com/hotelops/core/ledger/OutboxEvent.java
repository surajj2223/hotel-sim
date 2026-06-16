package com.hotelops.core.ledger;

import com.hotelops.core.common.enums.OutboxStatus;
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
 * SCH-060 — transactional outbox event.
 *
 * A booking/payment write enqueues an event in the SAME transaction as the write itself.
 * The ledger processor ({@link OutboxProcessor} — Package C) polls PENDING events and
 * produces {@link LedgerPosting}s idempotently.
 *
 * This decoupling means ledger failures never roll back the booking/payment transaction.
 */
@Entity
@Table(name = "outbox_event")
@Getter
@Setter
@NoArgsConstructor
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    /** e.g. 'PAYMENT_CAPTURED', 'REFUND_SETTLED' */
    @Column(name = "event_type", nullable = false)
    private String eventType;

    /** e.g. 'PAYMENT', 'BOOKING' */
    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    /** Full event detail — stored verbatim; not queried on. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;

    /**
     * SCH-061 — stamped in the same atomic UPDATE that claims the row (PENDING→PROCESSING),
     * and re-stamped when a stale PROCESSING row is reclaimed. Drives the stale-reclaim cutoff
     * ({@link OutboxProcessor}). Null for rows that predate the V5 migration — those are never
     * reclaimed, which is acceptable for the POC.
     */
    @Column(name = "claimed_at")
    private OffsetDateTime claimedAt;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
