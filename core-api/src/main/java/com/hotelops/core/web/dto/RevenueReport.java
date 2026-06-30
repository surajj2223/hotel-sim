package com.hotelops.core.web.dto;

import com.hotelops.core.common.enums.Vertical;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * API-016 getRevenue response body (WAVE0_02_OPENAPI.yaml, FROZEN).
 *
 * Revenue split by vertical over a half-open {@code [from, to)} window on posting time. All
 * amounts are MINOR UNITS. Per the contract:
 *   - {@code gross}         = Σ REVENUE postings for the vertical in the window;
 *   - {@code refundedTotal} = |Σ REFUND_REVERSAL postings| (reversals are stored negative;
 *     this is sign-flipped to a non-negative magnitude);
 *   - {@code net}           = gross − refundedTotal — a DERIVED IDENTITY, never a stored field.
 *
 * {@code groupBy} is vertical only (charter §9 minimal — no day / capture-mode variants).
 * Verticals with no activity in the window are omitted (they never appear in the grouped
 * aggregate). Entities are never serialised — this DTO is assembled in {@code ReportingService}.
 */
public record RevenueReport(
        Window window,
        String currency,
        List<RevenueByVertical> byVertical,
        RevenueTotals totals
) {
    public record Window(OffsetDateTime from, OffsetDateTime to) {}

    public record RevenueByVertical(Vertical vertical, long gross, long refundedTotal, long net) {}

    public record RevenueTotals(long gross, long refundedTotal, long net) {}
}
