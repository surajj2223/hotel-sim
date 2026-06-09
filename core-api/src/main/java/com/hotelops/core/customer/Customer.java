package com.hotelops.core.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SCH-001, SCH-002 — customer identity + contact.
 *
 * {@code shopperReference} is minted once at creation (format SHPR-{token}) and is NEVER
 * changed thereafter (INV-001).  Immutability is enforced by {@link CustomerService};
 * the DDL CHECK constraint guards the format.
 */
@Entity
@Table(name = "customer")
@Getter
@Setter
@NoArgsConstructor
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    /**
     * SCH-002 / INV-001 — stable opaque shopper identifier (SHPR-{token}).
     * Minted once by {@link CustomerService#createCustomer}; never updated.
     * No public setter exposed — updates go through the service which enforces INV-001.
     */
    @Column(name = "shopper_reference", nullable = false, unique = true, updatable = false)
    private String shopperReference;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "email")
    private String email;

    @Column(name = "phone")
    private String phone;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** SCH-003 — preferences cascade-deleted with the customer. */
    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL, orphanRemoval = true,
               fetch = FetchType.LAZY)
    private List<CustomerPreference> preferences = new ArrayList<>();

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
