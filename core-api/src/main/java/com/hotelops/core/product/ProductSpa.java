package com.hotelops.core.product;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * SCH-012 — JTI child for SPA vertical.
 *
 * {@code concurrentSlots} is the inventory figure: how many parallel bookings of this
 * treatment can run simultaneously.
 */
@Entity
@Table(name = "product_spa")
@DiscriminatorValue("SPA")
@PrimaryKeyJoinColumn(name = "product_id")   // JTI child PK column (SCH-012); matches frozen schema
@Getter
@Setter
@NoArgsConstructor
public class ProductSpa extends Product {

    /** e.g. 'MASSAGE_60', 'FACIAL_45' */
    @Column(name = "treatment_kind", nullable = false)
    private String treatmentKind;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    /** Optional preference target for therapist gender matching. */
    @Column(name = "therapist_gender")
    private String therapistGender;

    /** Inventory: parallel capacity per slot. */
    @Column(name = "concurrent_slots", nullable = false)
    private int concurrentSlots;
}
