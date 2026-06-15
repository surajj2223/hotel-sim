-- V4: SCH-022 chk_line_amount relaxed — line_amount is now strategy-owned [RX-002]
--
-- The frozen equality `line_amount = unit_price * quantity` no longer holds for
-- duration-priced verticals: a ROOM line's debt is unit_price * quantity * nights
-- (KNOWN_LIMITATION_ROOM_PRICING.md), while verticals with no duration dimension stay
-- unit_price * quantity. Recorded as a formal SCH-022 supersession in RX-002 (see the
-- Freeze Ledger, WAVE0_00_OVERVIEW.md §1b). unit_price keeps its meaning (the per-unit
-- rate); only the line_amount invariant is relaxed.
--
-- Replacement invariant keeps a positive value and a no-under-count floor: line_amount is
-- never less than one period's worth (unit_price * quantity), which holds for ROOM
-- (nights >= 1) and as an equality for non-duration verticals.
ALTER TABLE booking_line DROP CONSTRAINT chk_line_amount;
ALTER TABLE booking_line ADD CONSTRAINT chk_line_amount
  CHECK (line_amount > 0 AND line_amount >= unit_price * quantity);
