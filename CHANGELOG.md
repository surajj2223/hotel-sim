# Changelog

Engineering changelog for the hotel-sim POC. Contract freeze/version history lives in the
`WAVE0_0X` artifacts' own changelog sections — this file tracks implementation work that is
not itself a contract change.

## Added — Folio completion lifecycle: `completeLine` + `completeFolio` (Slice 2) [API-014, API-015, ENM-002, ENM-003, INV-007, RX-003]

Drove the two previously-dead terminal enum values (`BookingStatus.COMPLETED`,
`BookingLineStatus.COMPLETED`) with two operational lifecycle writes, per
`DESIGN_FOLIO_COMPLETION.md`. Additive over the frozen contract (API-014/API-015 frozen
first; see `PROPOSAL_API_014_015_FOLIO_COMPLETION.md`). No schema change, no migration.

- **`completeLine` (API-014)** — `POST /bookings/{bookingId}/lines/{lineId}/complete`:
  ACTIVE → COMPLETED. **Ungated** (posts nothing, moves no money — mirrors the ungated
  `cancelLine`) and with **no folio side effect** (completing a line never flips the
  booking — the deliberate asymmetry with cancel's rollup, DESIGN §2). CANCELLED line → 409;
  a line not under the path booking → 404.
- **`completeFolio` (API-015)** — `POST /bookings/{bookingId}/complete`: CONFIRMED →
  COMPLETED. **INV-007 gated** (`X-Human-Auth` asserted before the service call, like
  `capturePayment`; absent → 428). Write-time revalidation fails loudly (409
  `FolioNotCompletable`, `currentState` naming the straggler `lineId`s + live `customerOwes`)
  unless both **C1** (every non-CANCELLED line is COMPLETED) and **C2** (`customerOwes == 0`,
  RX-003 — refund-driven `netRevenue` is irrelevant and does not block) hold. Idempotent 200
  on an already-COMPLETED booking; CANCELLED (terminal) and PENDING (empty) → 409.

Notable: `completeFolio` deliberately does **not** call `recalculateTotals` — that roll-up
sums **ACTIVE-only** lines (`sumActiveLineAmounts`), so recomputing after lines are COMPLETED
would drop the completed lines' debt and zero the total. The freshly-loaded booking already
carries the server-maintained totals (line mutations + capture/refund webhooks keep them
current), read inside the completion transaction — fresh and correct for COMPLETED lines.

Error mapping reuses the existing 409 `StateConflict` envelope via a new
`FolioNotCompletableException` (domain-only fields, no web coupling) + `FolioCompletionState`
DTO. New endpoints re-read the folio via `getById` after the write (the proven `addLine`
pattern) to avoid mapping a detached lazy proxy.

Proofs: `FolioCompletionApiTest` (10 cases — line complete / CANCELLED-line 409 / 404;
folio happy path; C1 straggler; C2 owes≠0; refunded-but-settled completes, ties RX-003 §5;
idempotent 200 vs CANCELLED 409; INV-007 428). Full core-api suite green (147/147).
Freeze Ledger API-014/API-015 rows → **FROZEN · DONE**.

> Known follow-up (out of Slice 2 scope): `sumActiveLineAmounts` is ACTIVE-only, so a
> `recalculateTotals` triggered *after* line completion (e.g. a refund webhook on a
> checked-out folio, Stage 5) would drop COMPLETED-line debt from `totalAmount`. Flagged for
> the refund-after-completion slice.

## Changed — RX-003: `balance` split into `customerOwes` + `netRevenue` (Slice 1) [RX-003]

Replaced the single overloaded `balance` (`total − paid + refunded`) with two
separately-derived, honestly-named read-model fields, per RX-003 §2:

- **`customerOwes` = `max(0, total_amount − amount_paid)`** — the settlement predicate
  ("paid" == `customerOwes == 0`). Refunds never appear in it; the clamp keeps an
  over-capture from rendering as a negative receivable.
- **`netRevenue` = `amount_paid − amount_refunded`** — the finance read.

This fixes the latent defect where a paid-then-refunded folio (e.g. 600 → pay 600 →
refund 100) read `balance = +100`, falsely re-opening a customer debt that the refund did
not create. Read-model only — no capture, refund, posting, or ledger behaviour changed.

- **Schema:** additive Flyway `V6__balance_split.sql` drops + recreates the `booking_balance`
  view (SCH-021) with `customer_owes` / `net_revenue`. `V1` (frozen) untouched.
- **Domain:** `Booking.getBalance()` removed outright (no alias — a stale reader must fail to
  compile) → `getCustomerOwes()` / `getNetRevenue()`; `BookingBalance` projection mirrors the
  new view columns; `BookingBalance.isPaid()` keys on `customerOwes == 0`.
- **DTO:** `FolioResponse.balance` → `customerOwes` + `netRevenue` (`ops-web` not present in
  repo; no UI consumer to update). Frozen `WAVE0_02_OPENAPI.yaml` keeps `balance` + its
  RX-003 banner — the forward spec lives in the ledger, not by mutating the frozen contract.
- **Beyond RX-003 §4's stated blast radius:** `BookingRepository.findUnpaid()` still encoded
  the superseded `total − paid + refunded > 0` predicate, which wrongly listed a
  fully-refunded booking as unpaid. Corrected to the D2 settlement predicate `total − paid > 0`.

Proofs: `PaymentApiTest.RX_003_paidThenRefunded_customerOwesZero_netRevenueRetained`
(end-to-end HTTP money loop → `customerOwes == 0`, `netRevenue == 500`);
`BookingEntityTest` (view split, fully-paid, refund-no-debt, `findUnpaid` excludes refunded);
existing `InvariantTest` / folio API tests migrated to the two fields.

Freeze Ledger (`WAVE0_00 §1b`) SCH-021-via-RX-003 row flipped to **FROZEN · DONE**.

## Changed — Docs reconciliation: Stage↔Wave mapping made explicit

Brought the narrative docs into agreement with shipped reality after the `stage-2` tag
(`f1043b1`). Stages now live *inside* Wave 1 (Waves = coarse phase; Stages = fine-grained
build progress): Wave 1 is delivered as a Stage march — Stage 1 (book a room) and Stage 2
(get paid — money loop, SPA availability, scoped cross-vertical allocation) complete; Stages
3–8 remain. The single authoritative status source remains the Freeze Ledger
(`WAVE0_00 §1b`); narrative docs defer to it.

- **`HS-08` (`08-delivery-plan.md` + `.html`):** corrected the Wave 0 table —
  `WAVE0_03_WEBHOOK_PSP_CONTRACT.md` **Deferred → Frozen** (it is FROZEN and fully
  implemented — the money loop); added a "Wave 1 delivery — Stage reality" section and
  annotated Tracks A–F as plan-of-record vs. actual linear-Stage execution; logged the
  drift correction; bumped to v0.2.
- **`README.md`:** Stage-2 framing on the status banner / head-body + projects tables;
  corrected the stale "ROOM only / 400 by design" availability claim (SPA availability
  shipped, Slice A3/A4 `spaAttributes`).
- **`docs/RUNNING.md`:** corrected the stale SPA-400 claim and the stale "outbound seam not
  wired" caveat (the `core-api → payments-sim` loop is live, PSP-001..017); noted scoped
  `lineCoverage` on payment-link creation and derived `revenuePosted` on folio reads.

Docs only — no behaviour change, no contract/source/schema edits, no tag changes.

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
