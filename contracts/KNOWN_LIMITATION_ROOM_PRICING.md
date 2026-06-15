# DECISION — ROOM line pricing corrected to rate × rooms × nights

**Status:** PROPOSED (awaiting sign-off) · **Crosses frozen contracts — flagged, not self-fixed**

## Defect
`BookingLine.lineAmount` is defined as *the cost to the customer for that line*.
For a multi-night room, the engine computed it as `basePrice × quantity` only —
`RoomStrategy.calculateUnitPrice` ignored `startsAt`/`endsAt` and returned the flat
base price. A 3-night stay (2026-07-01T15:00 → 2026-07-04T11:00) priced as £180
instead of £540. Observed: folio `totalAmount`/`balance` = 18000 while a hand-typed
auth/coverage of 54000 sat alongside it. The folio was the honest figure; the engine
under-priced the debt.

## Correction
Room line debt = **`unitPrice × number_of_rooms × number_of_nights`**, where:
- `unitPrice` remains the **per-night rate** (unchanged meaning; still snapshotted to
  `line.unitPrice` and still shown on the availability screen).
- `number_of_rooms` = `quantity`.
- `number_of_nights` = **calendar-date span**:
  `ChronoUnit.DAYS.between(startsAt.toLocalDate(), endsAt.toLocalDate())`.
  Chosen over raw-instant `DAYS.between` so cross-midnight stays count correctly and
  check-in/out times don't distort the count. (15:00→11:00 over Jul 1–4 = 3 ✓.)
- **0-night room line → rejected** with a clear error (no silent £0 room). [CONFIRM:
  reject vs. allow-as-day-use.]

`unitPrice` is unchanged in meaning; only `lineAmount` is corrected. Spa/F&B are
**not** night-multiplied — duration pricing is a Rooms concern and stays inside
`RoomStrategy`.

## Why it crosses a freeze (the reason this is a note, not a quiet patch)
1. **Frozen behaviour:** charter/`RoomStrategy` state "Stage 1 has no yield pricing."
   This corrects a *latent under-pricing bug*, not adds yield pricing — but it changes
   Room pricing output, so it is flagged.
2. **Package-A interface (`VerticalStrategy`) gains a method** `calculateLineAmount(...)`.
   The line-total computation moves out of `BookingService`'s hardcoded
   `unitPrice × quantity` and into the strategy, so each vertical owns whether duration
   applies (Option 3 — respects the strategy boundary). Additive to the interface, but
   the interface is a published seam.

## Touch points
- `VerticalStrategy` — add `long calculateLineAmount(productId, quantity, startsAt, endsAt)`.
- `RoomStrategy` — implement nights × rooms × rate; reject 0-night.
- `SpaStrategy` — implement `basePrice × quantity` (no nights).
- `BookingService.addLine` — set `lineAmount` from `strategy.calculateLineAmount(...)`;
  keep `unitPrice` from `calculateUnitPrice(...)`.
- Tests — RoomStrategy 3-night = rate×3, 0-night rejected; SpaStrategy unchanged.
- `calculateUnitPrice` and `AvailabilityController` — **unchanged** (rate still means rate).

## Reconciliation side-effect
After this lands, the scoped-payment Postman collection's room line computes to 54000
on its own; the previously hand-typed 54000 auth/coverage become a correctly-matched
£540 debt. The flow reconciles end-to-end.