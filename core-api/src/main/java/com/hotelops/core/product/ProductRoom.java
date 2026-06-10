package com.hotelops.core.product;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * SCH-011 — JTI child for ROOM vertical.
 *
 * {@code roomCount} is the inventory figure: how many rooms of this type exist.
 * Availability = roomCount minus active booking lines overlapping the requested window
 * (computed by the RoomStrategy in Package B).
 */
@Entity
@Table(name = "product_room")
@DiscriminatorValue("ROOM")
@PrimaryKeyJoinColumn(name = "product_id")   // JTI child PK column (SCH-011); matches frozen schema
@Getter
@Setter
@NoArgsConstructor
public class ProductRoom extends Product {

    /** e.g. 'LOW', 'MID', 'HIGH' */
    @Column(name = "floor_band")
    private String floorBand;

    /** e.g. 'KING', 'TWIN', 'DOUBLE' */
    @Column(name = "bed_type")
    private String bedType;

    @Column(name = "max_occupancy", nullable = false)
    private int maxOccupancy = 2;

    @Column(name = "quiet", nullable = false)
    private boolean quiet = false;

    /** Inventory: rooms of this type available in the property. */
    @Column(name = "room_count", nullable = false)
    private int roomCount;
}
