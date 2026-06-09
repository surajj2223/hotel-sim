package com.hotelops.core.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * SCH-003, SCH-004 — customer preference key/value, cross-vertical, open-ended.
 *
 * The UNIQUE constraint on (customer_id, pref_key) enforces SCH-004: one value per key.
 * Cascade-deleted when the customer is deleted (SCH-003).
 */
@Entity
@Table(
    name = "customer_preference",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_pref_per_customer_key",
        columnNames = {"customer_id", "pref_key"}
    )
)
@Getter
@Setter
@NoArgsConstructor
public class CustomerPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    /** e.g. 'floor', 'dietary', 'spa_therapist' */
    @Column(name = "pref_key", nullable = false)
    private String prefKey;

    /** e.g. 'high', 'vegan', 'female' */
    @Column(name = "pref_value", nullable = false)
    private String prefValue;

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
