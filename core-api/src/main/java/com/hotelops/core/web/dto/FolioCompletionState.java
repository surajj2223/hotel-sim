package com.hotelops.core.web.dto;

import com.hotelops.core.common.enums.BookingStatus;

import java.util.List;
import java.util.UUID;

/**
 * API-015 — {@code currentState} payload of a 409 {@code FolioCompletionConflict}
 * (WAVE0_02_OPENAPI.yaml). Echoes the live truth when {@code completeFolio} is rejected by
 * write-time revalidation, so the caller can surface it and re-read:
 *
 * <ul>
 *   <li>{@code status} — the booking's current status, unchanged by the rejected write.</li>
 *   <li>{@code customerOwes} — live settlement figure (RX-003), minor units. C2 requires 0.</li>
 *   <li>{@code incompleteLineIds} — C1: every non-CANCELLED line not yet COMPLETED (the
 *       ACTIVE stragglers). Empty when only C2 failed or on a terminal-state attempt.</li>
 * </ul>
 */
public record FolioCompletionState(
        BookingStatus status,
        long customerOwes,
        List<UUID> incompleteLineIds
) {
}
