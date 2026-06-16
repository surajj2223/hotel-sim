# Changelog

Engineering changelog for the hotel-sim POC. Contract freeze/version history lives in the
`WAVE0_0X` artifacts' own changelog sections — this file tracks implementation work that is
not itself a contract change.

## Changed — WHK-016 / API-008 scoped allocation frozen (governance reconciliation)

Recorded in the Freeze Ledger (`WAVE0_00 §1b`) that WHK-016 (scoped payment→line ledger
allocation) and the API-008 `lineCoverage` / `revenuePosted` amendment (Slice S2) are
**FROZEN · DONE** — closing the §1c lapse where merged code (PRs #22–#24) and the runnable
Postman folio proof (`9e7476e`) depended on artifacts still listed DRAFT. Docs/contract
markers only; no behaviour change. Impl: bb9395c, af8c42e, 7674e63, c2ea71f.

## Fixed — ROOM line pricing: rate × rooms × nights

ROOM line debt was computed as `unit_price × quantity` only, ignoring the stay length: a
3-night room priced as one night, so a multi-night folio under-stated `totalAmount` /
`balance` (the latent defect in `contracts/KNOWN_LIMITATION_ROOM_PRICING.md`). Corrected so a
ROOM line's `lineAmount = unit_price × quantity × nights`, where `nights` is the calendar-date
span `DAYS.between(startsAt.toLocalDate(), endsAt.toLocalDate())` (cross-midnight safe;
check-in/out times don't distort the count). A 0-night room line is rejected loudly
(`IllegalArgumentException`) — no silent £0 room.

- **New** `VerticalStrategy.calculateLineAmount(productId, quantity, startsAt, endsAt)`
  (Option 3 — the strategy owns the line total, so duration pricing lives in `RoomStrategy`
  only). `RoomStrategy` multiplies by nights; `SpaStrategy` returns `base × quantity` (no
  nights). `BookingService.addLine` now sets `lineAmount` from the strategy instead of the
  hardcoded `unitPrice × quantity`.
- **`unit_price` meaning is unchanged** — it stays the per-night rate, snapshotted to
  `line.unitPrice` and returned by `calculateUnitPrice` for the availability screen. Nights
  are NOT folded into the unit price.
- **Crossed two frozen seams — flagged & arbitrated, not self-fixed:**
  - The published Package-A `VerticalStrategy` interface gained `calculateLineAmount`
    (additive).
  - The frozen DB invariant **SCH-022 `chk_line_amount`** (`line_amount = unit_price *
    quantity`) blocked multi-night persistence. Relaxed to a positive no-under-count floor
    (`line_amount > 0 AND line_amount >= unit_price * quantity`) via additive Flyway
    `V4__line_amount_strategy_owned.sql`, recorded as
    `contracts/refactor-x/RX-002-line-amount-strategy-owned.md` and the Freeze Ledger
    (`WAVE0_00 §1b`).
- **Tests:** `RoomStrategyTest` (3-night = rate×3, multi-room = rate×3×rooms, 0-night
  rejected, 1-night = rate); `SpaStrategyTest` (multi-day window still `base × quantity`, no
  nights); `BookingEntityTest` (DB rejects below-floor `line_amount`); `BookingFlowApiTest`
  (end-to-end 2-night room persists `lineAmount = rate × nights`, `unitPrice` stays the rate).
  Payment/scoping/webhook fixtures use genuine 1-night windows (subject is payments, not
  pricing) so `lineAmount == rate` and their assertions are unchanged. Full `core-api` suite:
  131 passed, 0 failed, 0 skipped.

## Stage 2 · Feature 2 · Part 1C-a — `payments-sim` real endpoints self-emit webhooks

Closes the money loop on the **real, always-on** request endpoints. Previously only the
`@Profile("test")` `TestTriggerController` (`/v1/test/...`) fired settlement webhooks, so a
demo against the production wiring left the loop open (core-api requested, the sim never
called back). Now the real `POST /v1/payments/{ref}/captures`, `/cancellations`, and
`/v1/payments/{ref}/refunds` settle and emit their webhook asynchronously after commit, with
no `/v1/test` step.

- **New** `PspWebhookEmitter` (non-transactional) — sequences the existing
  `PspTriggerService.prepare*` settlement (tx2) then `WebhookDispatcher.dispatch(..., sync=false)`
  after commit. No duplicated state-flip or envelope logic; emitted `idempotencyKey`
  (`pspRef:EVENTCODE:seq`) stays byte-identical to the test path, so core-api's WHK-005
  inbox dedupe is unchanged.
- `PaymentController` / `RefundController` now delegate the post-commit emit to the new bean
  (they remain the non-transactional sequencer; the HTTP call never sits inside a DB tx —
  PSP-006 / GAP-2 discipline). `202` returns immediately; delivery is fire-and-forget,
  single-attempt, no-retry (PSP-007/008).
- **Async-only on the real path.** The inline `?sync=true` seam stays exclusive to
  `@Profile("test")` `TestTriggerController` (WHK-015 stays unreachable in prod).
- **New config** `psp-sim.settlement-delay-ms` (env `PSP_SIM_SETTLEMENT_DELAY_MS`,
  default `0`) — delays the webhook on the executor thread only (never the request thread,
  never the sync seam). A non-zero value makes the out-of-band `AUTHORISED → CAPTURED` flip
  visible in a demo. Wired in `application.yml` and `docker-compose.yml`.
- **AUTHORISATION stays test-only** — it represents the customer paying (pay-web deferred,
  RX-001), so it has no real trigger and remains the `/v1/test` customer-checkout stand-in.
  IMMEDIATE capture rides the AUTHORISATION→CAPTURE chain, so the real `/captures` self-emit
  only ever runs for MANUAL.
- **Tests** — new `RealPathAutoEmitTest` proves a real `/captures` self-emits a signed
  CAPTURE webhook (deterministic `idempotencyKey`) and settles the row with **no `/v1/test`
  call and no `test` profile**. `AuthoriseTriggerSyncTest` now drives capture via the real
  async path (polls the receiver). `Capture/Cancellation/RefundApiTest` happy-path
  assertions updated to the now-synchronous settled state.
- **Postman** collection folders 04/05 — removed the three `/v1/test/...` settle steps,
  relabelled the authorise step as the customer-checkout stand-in, and added poll-until-state
  steps where settlement now lands out-of-band.

No frozen `WAVE0_0X` contract was modified.
