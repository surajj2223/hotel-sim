package com.hotelops.core.product;

import com.hotelops.core.common.enums.Vertical;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * SCH-014 — JTI child for EVENT vertical.
 *
 * A specific departure of an experience (e.g. Hyde Park horse ride at 10:00 on Saturday).
 * {@code capacity} is the inventory figure: seats available for this departure.
 */
@Entity
@Table(name = "product_event")
@DiscriminatorValue("EVENT")
@PrimaryKeyJoinColumn(name = "product_id")   // JTI child PK column (SCH-014); matches frozen schema
@Getter
@Setter
@NoArgsConstructor
public class ProductEvent extends Product {

    // See ProductRoom: mirror the JTI discriminator so getVertical() is correct pre-reload.
    { setVertical(Vertical.EVENT); }

    @Column(name = "departs_at", nullable = false)
    private OffsetDateTime departsAt;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    /** Inventory: seats available for this departure. */
    @Column(name = "capacity", nullable = false)
    private int capacity;

    @Column(name = "location")
    private String location;
}
