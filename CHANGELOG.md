# Changelog

Engineering changelog for the hotel-sim POC. Contract freeze/version history lives in the
`WAVE0_0X` artifacts' own changelog sections — this file tracks implementation work that is
not itself a contract change.

## Changed — Docs refresh to Stage 3.1 (three verticals, folio lifecycle, reports)

Brought the three narrative docs into agreement with shipped reality after the `stage-3.1`
tag. They were last touched at the `stage-2` era and described a two-vertical, no-completion,
no-reports system. Since then the F&B vertical (strategy + availability `fnbAttributes` +
booking), the folio-completion lifecycle (`completeLine`/`completeFolio`, API-014/015), and
the charter §9 reporting reads (`getRevenue` API-016, `listUnpaidBookings` API-017) all
shipped. The single authoritative status source remains the Freeze Ledger (`WAVE0_00 §1b`);
these docs defer to it. Docs/status only — no code, no contract, no Postman.

- **`README.md`:** status banner, two-heads Body row, and Projects table bumped Stage 2 →
  Stage 3.1; `/availability` rows corrected (FNB now registered, returns `fnbAttributes` —
  only EVENT 400s); F&B marked wired end-to-end in the Business Domain list and strategy
  prose; the four new endpoints added to the live-endpoint tables (`/bookings/{id}/complete`,
  `/bookings/{id}/lines/{lineId}/complete`, `/reports/revenue`, `/reports/unpaid-bookings`);
  the Capability-Surface "not all live yet" hedge dropped (reports are live; EVENT +
  `ops-web`/MCP remain); Delivery Waves now reads Stages 1–3 closed.
- **`docs/RUNNING.md`:** banner bumped to current state; `/availability` + SPA/FNB test note
  corrected (FNB returns `fnbAttributes`; only EVENT 400s); added Folio-completion and Reports
  endpoint tables. The minimal single-ROOM seed + curl happy-path are unchanged (still valid).
- **`docs/system-design/prd/08-delivery-plan.md`:** Build Status Stage 2 → Stage 3.1; Stage
  map split out a closed Stage 3 row (F&B, completion, reports); Stages 4–8 narrowed to EVENT
  + remaining depth; version 0.2 → 0.3, date → 2026-06-30; Contract-Drift Log row added.

Docs only — no behaviour change, no contract/source/schema edits, no tag changes. The EVENT
vertical, `ops-web`, and the MCP server remain genuinely unbuilt.

## Changed — Reports API-016/017 frozen (Desk sign-off) [API-016, API-017]

Contract-status flip only — no behavioural change. `getRevenue` (API-016, `GET /reports/revenue`)
and `listUnpaidBookings` (API-017, `GET /reports/unpaid-bookings`), specced as DRAFT in PR #39,
are now **FROZEN** on Desk sign-off. The eight DRAFT markers in `WAVE0_02_OPENAPI.yaml` (line-1
header, reports-tag prose, the two path comments, the two schema comments, and the
`x-requirements-reports` block-level `status`) read FROZEN, and the two §1b Freeze Ledger rows in
`WAVE0_00_OVERVIEW.md` flip DRAFT→FROZEN. No schema, path, DTO, or field change — status text
only; zero code. The implementation slice may now proceed.

## Changed — API-004 `fnbAttributes` frozen (Slice A5 sign-off) [API-004]

Contract-status flip only — no behavioural change. The `fnbAttributes` amendment on
`AvailabilityResult` (API-004), shipped in PR #36, is now **FROZEN** on Desk sign-off. The
three DRAFT markers (the `WAVE0_02_OPENAPI.yaml` schema comment, the `amendmentA5` status under
`x-requirements-stage2`, and the §1b Freeze Ledger row in `WAVE0_00_OVERVIEW.md`) read FROZEN;
the field, DTO, mapper, and tests are unchanged. This authorises the already-shipping field.

## Added — API-004 `fnbAttributes` (Slice A5, draft) [API-004]

Added a nullable `fnbAttributes` object to the `AvailabilityResult` DTO (API-004), the third
vertical-specific attribute slot alongside `roomAttributes` (ROOM) and `spaAttributes` (SPA,
Slice A4). It closes the `fnbAttributes` deferral noted when `FnbStrategy` and the F&B HTTP
exercise shipped — F&B availability rows now carry their own attribute block instead of none.

- **Minimal field set, no schema migration** — `FnbAttributes(servicePeriod, seatingMinutes,
  coversCapacity)`, read straight from `ProductFnb`. No cuisine/dietary (those would need new
  `product_fnb` columns; out of scope).
- **Additive, backward-compatible** — `DtoMapper.toAvailabilityResult` gains an
  `instanceof ProductFnb` branch; the ROOM and SPA branches are untouched, so existing
  ROOM/SPA responses are byte-identical. Populated for FNB, null for every other vertical;
  `roomAttributes`/`spaAttributes` stay null on FNB rows.
- **Contract amendment is DRAFT, not frozen.** `WAVE0_02_OPENAPI.yaml` (frozen) carries the
  `fnbAttributes` schema as a `# DRAFT AMENDMENT (Slice A5)` block plus an `amendmentA5`
  sibling entry under `x-requirements-stage2` API-004 (A3/A4 blocks untouched). The
  DRAFT→FROZEN flip is Desk's sign-off, deliberately left out of this slice.

Proofs: `FnbAvailabilityApiTest` (MockMvc + Testcontainers) — FNB row carries `fnbAttributes`
populated (DINNER / 120 / 40) with `roomAttributes` and `spaAttributes` null; ROOM row carries
no `fnbAttributes` (cross-vertical null integrity). Full `core-api` suite green.

## Added — full payment-lifecycle Postman coverage (capture / complete / refund / cancel)

Extended the runnable Postman collection
(`docs/runnable-postman-collection/hotel-sim-postman-collection.json`) to drive the full
payment lifecycle end-to-end. It previously stopped at scoped link/auth + IMMEDIATE spa
settle; capture, folio completion, refund, and cancellation were all real, wired controller
endpoints that nothing exercised over HTTP. Strictly additive — single artifact edit, no
code and no contract change.

- **Folders 08–12 appended** (00–07 untouched, not renumbered):
  - **08 — Capture the room (MANUAL → revenue):** capture `{{roomPaymentId}}` (gated) →
    poll until the self-emitted `CAPTURE` webhook settles → room line `revenuePosted` flips
    `0 → 54000`, folio `customerOwes == 0`.
  - **09 — Pay the F&B folio (IMMEDIATE):** scoped link → `authorise?sync=true`
    (auth+capture in one chain) → F&B line `revenuePosted == 9000`, `netRevenue == 9000`.
  - **10 — Complete the folios:** `completeLine` each non-cancelled line (ungated) →
    `completeFolio` (gated) → `COMPLETED`; includes an **idempotent re-complete** probe
    asserting **200, not 409**. Repeated for the F&B booking.
  - **11 — Refund probe (partial):** refund part of the captured spa payment (gated) →
    poll until the self-emitted `REFUND` webhook settles → `netRevenue` drops by the refund
    while `customerOwes` stays `0` (**RX-003**: refund reduces net, not settlement).
  - **12 — Cancel probe:** mints a *fresh* uncaptured MANUAL auth on a throwaway booking
    (the room auth is now captured and uncancellable), cancels it (gated) → polls until the
    self-emitted `CANCELLATION` webhook settles → proves **no ledger posting** results
    (**INV-006**: cancel of an uncaptured auth posts nothing).
- **Async settlement pattern (corrected during verification).** capture/cancel/refund return
  `202`; the core-api action drives the PSP over HTTP and `payments-sim` **self-emits** the
  settlement webhook back to core-api off-thread (the "1C-a" emitter), and the per-line
  ledger posting (`revenuePosted`) lands via the outbox poller (`@Scheduled(fixedDelay=5000)`,
  ~5s). So each step calls the core-api action (`202`), then **polls** the payment/folio
  (`setNextRequest` + ~1s bounded delay) until the terminal state — never asserting on the
  `202`. There is deliberately **no** `/v1/test` capture/cancel/refund trigger: the real PSP
  endpoints already self-emit, so such a trigger would `409 NO_CAPTURE_QUEUED`. The `/v1/test`
  **authorise** trigger is still used (folders 02/04/09/12) — there is no core-api "authorise"
  action, so it stands in for the deferred pay-web checkout.
- **Negative re-probes (pass-as-designed):** capture without `X-Human-Auth` → 4xx;
  `completeFolio` while a line is still ACTIVE → 409 with current state.
- **Pre-existing breakage fixed (folders 03 & 05).** The collection was already red before
  this change, independent of the new work:
  - Folders 03 & 05 asserted `f.balance`, a field removed from `FolioResponse` by RX-003 /
    Flyway `V6` (split into `customerOwes` + `netRevenue`) — corrected to `customerOwes`.
  - Folder 05 asserted `amountAuthorised == 54000`; the system returns **62000**.
    `booking.amountAuthorised` is the documented D3 roll-up `SUM(amountAuthorised)` across all
    payments (a payment keeps its authorised amount after capture), so room `54000` + spa
    `8000` (IMMEDIATE auth+capture) = `62000`. Corrected the assertion and added a poll for the
    spa line's `revenuePosted` (it lags via the same outbox poller).
- **Prereq updated.** The collection description now calls for the smoke overlay
  (`docker compose -f docker-compose.yml -f docker-compose.smoke.yml up`), which sets
  `SPRING_PROFILES_ACTIVE=test` on `payments-sim` so the `/v1/test/...` authorise router is
  reachable.

Proof: ran the whole collection green top-to-bottom with Newman against the four-service
smoke stack on a fresh DB volume — **125 assertions, 0 failures** (61 requests incl. poll
re-runs, ~14s); negative probes pass-as-designed.

## Added — F&B HTTP exercise + Postman booking thread + OpenAPI prose refresh [API-004, ENM-001]

Closed the observability gap on the F&B vertical. `FnbStrategy` shipped already (PR #33) and
is HTTP-reachable through the generic `AvailabilityController` / `BookingService` — both
dispatch via the vertical strategy registry, and a line's vertical is derived from its
product, so F&B is bookable over the existing `/availability` + `/bookings/{id}/lines`
endpoints with no F&B-specific controller (charter §2: no vertical-only endpoints). The gap
was that nothing *exercised* the wired path. Strictly additive; no new contract freeze.

- **API-level test** — `FnbAvailabilityApiTest` (MockMvc + Testcontainers, with the same
  Docker `@BeforeAll` guard as `SpaAvailabilityApiTest`): `GET /availability?vertical=FNB`
  returns `coversCapacity` minus committed overlap; a 2-cover line booked via the generic
  `POST /bookings/{id}/lines` drops `availableUnits` by 2 and is priced `base × quantity`
  (9000 = 4500 × 2, **no nights factor**); the availability row carries **neither**
  `roomAttributes` **nor** `spaAttributes` (`fnbAttributes` deferred — F&B did not inherit
  another vertical's attribute block); ROOM availability is unbroken (regression).
- **Runnable Postman thread** — appended folder `07 · F&B booking thread` (availability →
  customer → folio → F&B line → folio shows the line priced `base × quantity`). Booking
  only; the IMMEDIATE pay/capture loop is already proven generically and is out of scope here.
- **OpenAPI prose refresh** — three stale descriptive lines in the frozen
  `WAVE0_02_OPENAPI.yaml` ("ROOM and SPA exercised; FNB … reserved") refreshed to "ROOM,
  SPA, and FNB are exercised; EVENT reserved for a later slice." Text-only, arbiter-approved
  logged edit to a frozen artifact (`WAVE0_00 §1b` + §7); no schema/enum/path/DTO change, no
  ID minted, not a re-freeze.

Proofs: `FnbAvailabilityApiTest` (green under Testcontainers); Postman folder `07 · F&B
booking thread` runnable end-to-end against the compose stack; full core-api suite passing.

## Added — F&B vertical strategy [ENM-001, SCH-013]

Brought the F&B (`FNB`) vertical online with `FnbStrategy`, a strict mirror of the shipped
`SpaStrategy`. Additive against an already-frozen contract surface — `ENM-001` (the `FNB`
enum value), `SCH-013` (`product_fnb`), and the `ProductFnb` JPA entity all pre-existed and
were frozen; no contract change. The strategy completes the third of the four verticals'
availability/pricing/capture concerns in one place.

- **availability** — `coversCapacity` minus committed overlap, reusing
  `ProductRepository.countCommittedQuantity` verbatim (no new overlap SQL; the caller
  supplies the window, exactly as Spa does — no service-period-derived window).
- **pricing** — `calculateUnitPrice` = `basePrice`; `calculateLineAmount` = `basePrice ×
  quantity` with **no nights factor** (duration pricing stays a Rooms concern, per
  `KNOWN_LIMITATION_ROOM_PRICING.md`).
- **capture** — `defaultCaptureMode()` = `IMMEDIATE`, F&B being the charter's canonical
  authorise-and-capture-together vertical (charter §6/§11, ENM-004).

Registered via the `VerticalStrategyRegistration` marker interface (registry pickup is
automatic). One seed F&B product (`Dinner Service`, DINNER, 40 covers, £45.00) added to the
Postman seed SQL. Deliberately out of scope (deferred, like `spaAttributes` was): an
`fnbAttributes` block on the availability DTO, any service-period-derived window, and the
Events strategy.

Proofs: `FnbStrategyTest` (7 cases — full capacity, overlap reduces, adjacency does not,
CANCELLED excluded, `unitPrice == base` + `IMMEDIATE` + `vertical() == FNB`, `lineAmount ==
base × qty` across a multi-day window proving no nights leak, non-FNB product rejected).
Green under Testcontainers; full core-api suite still passing.

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
