package com.hotelops.core.web.dto;

import com.hotelops.core.common.enums.Vertical;

import java.util.UUID;

/**
 * API-004 response item — AvailabilityResult (WAVE0_02_OPENAPI.yaml).
 *
 * unitPrice is MINOR UNITS (pence). availableUnits is the free count in the window after
 * ACTIVE committed lines (INV-003 read side). roomAttributes / spaAttributes are nullable per
 * the contract: each is populated only for its own vertical (ROOM / SPA), null otherwise
 * (API-004 Slice A4 amendment).
 */
public record AvailabilityResult(
        UUID productId,
        Vertical vertical,
        String name,
        long unitPrice,
        String currency,
        int availableUnits,
        RoomAttributes roomAttributes,
        SpaAttributes spaAttributes
) {

    /** Room attributes the operator/model judges on. */
    public record RoomAttributes(
            String floorBand,
            String bedType,
            boolean quiet,
            int maxOccupancy
    ) {
    }

    /**
     * SPA attributes the operator/model judges on (API-004 Slice A4). {@code therapistGender}
     * is the optional preference target for therapist-gender matching (charter §5).
     */
    public record SpaAttributes(
            String treatmentKind,
            int durationMinutes,
            String therapistGender,
            int concurrentSlots
    ) {
    }
}
