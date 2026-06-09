package com.hotelops.core.product;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * SCH-013 — JTI child for F&amp;B vertical.
 *
 * {@code coversCapacity} is the inventory figure: total covers for this service period.
 */
@Entity
@Table(name = "product_fnb")
@DiscriminatorValue("FNB")
@Getter
@Setter
@NoArgsConstructor
public class ProductFnb extends Product {

    /** e.g. 'BREAKFAST', 'LUNCH', 'DINNER' */
    @Column(name = "service_period", nullable = false)
    private String servicePeriod;

    /** Inventory: total covers available per service period. */
    @Column(name = "covers_capacity", nullable = false)
    private int coversCapacity;

    @Column(name = "seating_minutes", nullable = false)
    private int seatingMinutes = 120;
}
