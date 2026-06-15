package com.hotelops.core.product.vertical;

import com.hotelops.core.common.enums.CaptureMode;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Strategy interface — one implementation per vertical (Package B).
 *
 * Package A defines this interface so that BookingService (Package A) can perform
 * INV-003 availability re-checks without depending on concrete strategy implementations.
 * Package B provides {@code RoomStrategy}, {@code SpaStrategy}, etc.
 *
 * Every vertical must implement all three concerns in ONE place (not scattered if/else
 * branches across services).
 */
public interface VerticalStrategy {

    /**
     * Returns the number of units still available for the given product in the given
     * time window, accounting for all ACTIVE committed booking lines.
     *
     * This method is called inside a write transaction (INV-003); implementations must
     * ensure a consistent read (e.g. by reading within the same transaction).
     */
    int availableCapacity(UUID productId, OffsetDateTime startsAt, OffsetDateTime endsAt);

    /**
     * Calculate the price for the given quantity in the given window.
     * Used for write-time price re-validation (INV-003).
     * Returns the computed unit price in minor units (e.g. pence).
     */
    long calculateUnitPrice(UUID productId, int quantity, OffsetDateTime startsAt, OffsetDateTime endsAt);

    /**
     * Total line debt (minor units) for the given quantity and window.
     * Rooms multiply by nights; verticals without a duration dimension return
     * unitPrice × quantity. Computed at write time alongside the INV-003 re-checks.
     *
     * <p>This is deliberately separate from {@link #calculateUnitPrice}: the unit price
     * stays the per-unit rate (snapshotted to {@code line.unitPrice} and shown on the
     * availability screen), while this method owns whether a duration dimension applies.
     * See {@code contracts/KNOWN_LIMITATION_ROOM_PRICING.md}.
     */
    long calculateLineAmount(UUID productId, int quantity, OffsetDateTime startsAt, OffsetDateTime endsAt);

    /**
     * Default capture mode for this vertical.
     * Per-payment, vertical-defaulted, overridable by the caller.
     * ENM-004: IMMEDIATE (e.g. F&B) or MANUAL (e.g. Rooms).
     */
    CaptureMode defaultCaptureMode();
}
