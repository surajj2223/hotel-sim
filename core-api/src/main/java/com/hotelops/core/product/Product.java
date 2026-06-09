package com.hotelops.core.product;

import com.hotelops.core.common.enums.Vertical;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * SCH-010 — product base table (JTI root).
 *
 * Uses Joined-Table Inheritance (JTI): each vertical has its own child table
 * (SCH-011..014).  The {@code vertical} column is the discriminator — it is written
 * automatically by Hibernate based on the concrete subclass's {@link DiscriminatorValue}.
 * The field mapping with {@code insertable=false, updatable=false} allows us to read it
 * back as a typed enum without conflicting with Hibernate's discriminator mechanism.
 *
 * INV-002 is enforced by {@link ProductService}: every product row must have EXACTLY ONE
 * matching child row, created atomically in the same transaction.
 *
 * Money: {@code basePrice} is always in integer minor units (e.g. pence) — never float.
 */
@Entity
@Table(name = "product")
@Inheritance(strategy = InheritanceType.JOINED)
@DiscriminatorColumn(name = "vertical", discriminatorType = DiscriminatorType.STRING)
@Getter
@Setter
@NoArgsConstructor
public abstract class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    /**
     * Read-only mapping of the JTI discriminator column.
     * The value is written by Hibernate via the @DiscriminatorValue on each subclass.
     * With {@code stringtype=unspecified} on the JDBC connection, Postgres casts the
     * string literal to the native {@code vertical} enum type.
     */
    @Column(name = "vertical", insertable = false, updatable = false, nullable = false)
    @Enumerated(EnumType.STRING)
    private Vertical vertical;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    /** Minor units (e.g. pence); never float/double. SCH-010 constraint: >= 0. */
    @Column(name = "base_price", nullable = false)
    private long basePrice;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency = "GBP";

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
