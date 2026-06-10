package com.hotelops.core.web.dto;

import com.hotelops.core.common.enums.Vertical;

import java.util.UUID;

/**
 * API-004 response item — AvailabilityResult (WAVE0_02_OPENAPI.yaml).
 *
 * unitPrice is MINOR UNITS (pence). availableUnits is the free count in the window after
 * ACTIVE committed lines (INV-003 read side). roomAttributes is nullable per the contract.
 */
public record AvailabilityResult(
        UUID productId,
        Vertical vertical,
        String name,
        long unitPrice,
        String currency,
        int availableUnits,
        RoomAttributes roomAttributes
) {

    /** Room attributes the operator/model judges on. */
    public record RoomAttributes(
            String floorBand,
            String bedType,
            boolean quiet,
            int maxOccupancy
    ) {
    }
}
