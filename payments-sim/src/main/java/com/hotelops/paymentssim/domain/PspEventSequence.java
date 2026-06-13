package com.hotelops.paymentssim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * PSP-011 — per (pspReference, eventCode) monotonic sequence used to build the deterministic
 * {@code idempotencyKey} every emitted webhook carries (WHK-003). A redelivery reuses the row;
 * seq is NOT incremented on the normal trigger path. Table created by V1__psp_sim_schema.sql.
 */
@Entity
@Table(name = "psp_event_sequence")
@IdClass(PspEventSequenceId.class)
@Getter
@Setter
@NoArgsConstructor
public class PspEventSequence {

    @Id
    @Column(name = "psp_reference", nullable = false)
    private String pspReference;

    @Id
    @Column(name = "event_code", nullable = false)
    private String eventCode;

    @Column(name = "seq", nullable = false)
    private int seq = 1;

    @Column(name = "last_emitted_at", nullable = false)
    private OffsetDateTime lastEmittedAt;

    @PrePersist
    void onCreate() {
        if (lastEmittedAt == null) lastEmittedAt = OffsetDateTime.now();
    }
}
