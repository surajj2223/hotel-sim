# Wave 0 — Foundation & Frozen Contracts (Overview)

> **Purpose of Wave 0.** Produce the frozen seams that let Wave 1 agents build in
> parallel without coordinating with each other. Everything here is a *contract*.
> Once signed off, contracts are **frozen** — changed only via the protocol below.

This overview is the entry point. Read it first, then open the artifact for the seam
you implement.

---

## 1. The artifact set

| File | What it freezes | Primary consumers |
|------|-----------------|-------------------|
| `WAVE0_00_OVERVIEW.md` (this) | How to use the set; freeze rule; change protocol | Everyone |
| `WAVE0_01_SCHEMA.sql` | Postgres DDL — all tables, types, constraints, indexes; enum/glossary | `core-api` (domain, finance, payment), integration owner |
| `WAVE0_02_OPENAPI.yaml` | `core-api` HTTP contract — every read/write endpoint, DTOs, error envelope | `ops-web`, MCP (Wave 2), `core-api` controllers |
| `WAVE0_03_WEBHOOK_PSP_CONTRACT.md` | `payments-sim` event vocabulary, payloads, idempotency; `core-api` consumer rules | `payments-sim`, `pay-web`, `core-api` payment orchestration |
| `WAVE0_04_SCAFFOLD.md` | Repo layout, docker-compose, health checks, run instructions | Integration owner; every package at setup |

> ⚠️ **The `pay-web` mention in the `WAVE0_03` consumer cell above is superseded in part by [RX-001](refactor-x/RX-001-psp-direction-and-statefulness.md)** (see Freeze Ledger §1b). Original text preserved; do not build against the superseded portion without checking the ledger.

Glossary/enums live **inside** `WAVE0_01_SCHEMA.sql` (as the single source of truth for
allowed values) and are referenced — never re-defined — by the other files.

---

## 1a. Requirement IDs (the spine of accountability)

Every contract artifact assigns a **stable ID** to each requirement (table, endpoint,
rule, enum, scaffold step). IDs never change once frozen; if a requirement is retired, its
ID is marked `RETIRED` and never reused. Briefs, commits, PRs, and tests **must reference
these IDs**, so any unit of work traces back to a frozen requirement.

| Prefix | Domain | Lives in |
|--------|--------|----------|
| `ENM-` | Enums / glossary / controlled vocabularies | `01_SCHEMA.sql` |
| `SCH-` | Tables, columns, constraints, indexes, invariants | `01_SCHEMA.sql` |
| `API-` | Endpoints, DTOs, error envelope, auth gate | `02_OPENAPI.yaml` |
| `WHK-` | PSP events, payloads, idempotency, consumer rules | `03_WEBHOOK_PSP_CONTRACT.md` |
| `SCF-` | Repo layout, compose, health checks, run steps | `04_SCAFFOLD.md` |

Cross-references are explicit: an `API-` DTO that maps to a table cites the `SCH-` ID; a
`WHK-` rule that updates a payment cites the `SCH-` and `API-` IDs it touches.

### Accountability surfaces (present in every artifact)
- **Requirements table** — `ID | requirement | acceptance criteria | depends-on`.
- **Accountability block** — owner, status (`DRAFT → FROZEN → IN-BUILD → DONE`), sign-off.
- **Verification log** — the implementing agent records, per ID: what was built, the
  commit/PR reference, and the test that proves it. Empty until Wave 1; this is the
  surface on which agents are held accountable.
- **Changelog** — every change to the artifact, per the protocol in §4.

---

## 1b. Freeze Ledger (THE single source of truth for status)

**This table is authoritative.** Every artifact's own status line, and every "status"
mention in `CLAUDE.md`, defers to this. If an artifact's header disagrees with this table,
this table wins and the header is the bug. Do not record freeze state anywhere else — in
particular, the changelog's first column is a **version number**, never a status.

A row is `FROZEN` only when (a) its content is reviewed and signed off, **and** (b) the
freeze is recorded here with the commit that did it. "Built-against in practice" is noted
where shipped code already depends on the artifact (which is itself why it is frozen).

| Artifact | Slice / IDs | Status | Frozen-at | Superseded-by | Evidence / notes |
|----------|-------------|--------|-----------|---------------|------------------|
| `WAVE0_01_SCHEMA.sql` | SCH-/ENM-/INV- (all) | **FROZEN** | pre-existing | [RX-002](refactor-x/RX-002-line-amount-strategy-owned.md) (`SCH-022 chk_line_amount` forward spec only) · [RX-003](refactor-x/RX-003-balance-definition-split.md) (`SCH-021 booking_balance` + INV-004 `getBalance()` forward spec only — verification records stand) | `V1__wave0_schema.sql` applied; SCH-001..071 referenced in entities; entity tests pass. Change only via a new Flyway migration. |
| `WAVE0_02_OPENAPI.yaml` | Stage 1 · API-001..007 | **FROZEN** | PR #6 (`ae63af2`) | — | Controllers + DTOs shipped; `BookingFlowApiTest` asserts the contract. |
| `WAVE0_02_OPENAPI.yaml` | API-004 widening (Slice A3) | **FROZEN** | Slice A3 | — | API-004 re-frozen: ROOM-only guard removed; endpoint resolves any registered vertical via `VerticalStrategyRegistry`. Three "ROOM only" text locations updated in contract. Proven by `SpaAvailabilityApiTest` (Testcontainers). |
| `WAVE0_02_OPENAPI.yaml` | API-004 `spaAttributes` (Slice A4) | **FROZEN** | (this slice) | — | Additive, backward-compatible: nullable `spaAttributes` object on `AvailabilityResult` (treatmentKind, durationMinutes, therapistGender, concurrentSlots), mirroring `roomAttributes` for ROOM. Populated for SPA, null for other verticals; `roomAttributes` stays null for SPA; existing ROOM responses byte-identical. Frozen on Desk sign-off; amendment block in `WAVE0_02` (`x-requirements-stage2` API-004 `amendment` + schema `# DRAFT AMENDMENT` comment — to be flipped on freeze). Unblocks charter §5 therapist-gender preference matching. Implementation + proof: `SpaAvailabilityApiTest`. |
| `WAVE0_02_OPENAPI.yaml` | API-004 `fnbAttributes` (Slice A5) | **FROZEN** | — | — | Additive, backward-compatible: nullable `fnbAttributes` object on `AvailabilityResult` (servicePeriod, seatingMinutes, coversCapacity — raw `product_fnb` config, no schema migration), mirroring `spaAttributes` for SPA / `roomAttributes` for ROOM. Populated for FNB, null for other verticals; `roomAttributes` and `spaAttributes` stay null for FNB; existing ROOM/SPA responses byte-identical. Closes the `fnbAttributes` deferral noted on the `FnbStrategy` / F&B-HTTP-exercise rows. Amendment block in `WAVE0_02` (`x-requirements-stage2` API-004 `amendmentA5` + schema `# DRAFT AMENDMENT (Slice A5)` comment) — sibling key, A3/A4 blocks untouched. Out of scope (would need a migration): cuisine/dietary. Implementation + proof: `FnbAvailabilityApiTest` (fnbAttributes populated; `roomAttributes`/`spaAttributes` null on FNB; `fnbAttributes` null on ROOM). |
| `WAVE0_02_OPENAPI.yaml` | Stage 2 · API-008..013 | **FROZEN** | (prior commit) | — | Payment paths + webhook receiver. Implemented in Feature 1 (right half). |
| `WAVE0_03_WEBHOOK_PSP_CONTRACT.md` | WHK-001..015 | **FROZEN** | (prior commit) | [RX-001](refactor-x/RX-001-psp-direction-and-statefulness.md) (pay-web framing only — WHK substance unchanged) | Pairs with API-008..013. Implemented in Feature 1 (right half). |
| `WAVE0_04_SCAFFOLD.md` | SCF-001, SCF-002, SCF-004 | **FROZEN** | pre-existing | — | Actuator/health/compose shipped and running. |
| `WAVE0_04_SCAFFOLD.md` | SCF-003 | **FROZEN** | pre-existing | [RX-001](refactor-x/RX-001-psp-direction-and-statefulness.md) (forward spec only — verification record stands) | Single-Postgres compose shipped & green. Forward spec replaced by SCF-005; SCF-003's §6 verification log is not edited. |
| RX-001 (`refactor-x/RX-001-psp-direction-and-statefulness.md`) | D1/D2/D3 + SCF-005 | **FROZEN** | (this commit) | — | Records `pay-web`-deferred, `payments-sim`-stateful with own Postgres, fail-loud / no-retry outbound + tx-ordering. Append-only; revise only via RX-002. |
| `WAVE0_04_SCAFFOLD.md` (via RX-001) | SCF-005 | **FROZEN** | (this commit) | — | Defined in RX-001 §4; compose/health detail in `WAVE0_05 §7` (drafting). Implementation pending Feature 2. |
| `WAVE0_05_PSP_API.md` | PSP-001..017 | **FROZEN** · IN-BUILD | (this commit) | — | Outbound PSP API + `payments-sim` internal schema + checkout-sim trigger + WHK-015 concrete sync seam + SCF-005 compose detail. Callback path corrected to `/webhooks/psp` (matches shipped `WebhookController`, API-013) at freeze. **Feature 2 Part A (1C-a) DONE: PSP-011/013/015/016 (+PSP-005 mint-on-event, PSP-008 reaffirmed).** **Part B (1C-b) DONE: PSP-001..004 outbound client, PSP-006 tx-ordering, PSP-007 fail-loud 502, PSP-017 four-service compose + live money-loop smoke — proofs in `WAVE0_05 §9a`. PSP-001..017 all DONE.** (Freeze unchanged; per-ID DONE lives in §9a.) |
| `WAVE0_03_WEBHOOK_PSP_CONTRACT.md` | WHK-016 | **FROZEN** · DONE | (this commit) | — | Signed off post-implementation (§1c reconciliation). Impl: `bb9395c` (allocation + `V3__payment_line_scoping.sql`), `af8c42e` (scoped refund). Proofs: `ScopedAllocationApiTest`, `ScopedRevenueHttpApiTest`, `LedgerCorrectnessTest` (unscoped fallback byte-identical). Runnable proof: Postman folio collection (`9e7476e`). |
| `WAVE0_02_OPENAPI.yaml` | API-008 `lineCoverage` (Slice S2) | **FROZEN** · DONE | (this commit) | — | Additive amendment signed off post-implementation. Impl: `7674e63` (`lineCoverage`), `c2ea71f` (`revenuePosted`). Proofs: `ScopedCreateLinkHttpApiTest`, `ScopedRevenueHttpApiTest`, `ImmediateCaptureApiTest`, `InvariantTest` (coverage-sum → 400). Base API-008 FROZEN status unchanged. |
| `WAVE0_02_OPENAPI.yaml` | API-014 completeLine (Stage 2 close-out / Slice 2) | **FROZEN · DONE** | (this commit) | — | Additive operational endpoint `POST /bookings/{bookingId}/lines/{lineId}/complete` — ACTIVE→COMPLETED line transition (ENM-003); ungated (mirrors `cancelLine`; posts nothing, no money, no inventory); no folio side effect; CANCELLED line → 409. Impl: `BookingService.completeLine` + `BookingController`. Proofs: `FolioCompletionApiTest` (line→COMPLETED no folio flip; CANCELLED line → 409; cross-booking line → 404). Drafted in `PROPOSAL_API_014_015_FOLIO_COMPLETION.md`; pairs with `DESIGN_FOLIO_COMPLETION.md`. |
| `WAVE0_02_OPENAPI.yaml` | API-015 completeFolio (Stage 2 close-out / Slice 2) | **FROZEN · DONE** | (this commit) | — | Additive operational endpoint `POST /bookings/{bookingId}/complete` — CONFIRMED→COMPLETED (ENM-002); INV-007 gated (428 else); write-time revalidation C1 (all non-CANCELLED lines COMPLETED) + C2 (`customerOwes == 0`, RX-003) → 409 `FolioNotCompletable` (names straggler `lineId`s + live `customerOwes`); idempotent 200 on already-COMPLETED; CANCELLED/PENDING → 409. `completeFolio` deliberately does **not** call `recalculateTotals` (ACTIVE-only sum would drop COMPLETED-line debt); reads server-maintained totals fresh in-tx. Impl: `BookingService.completeFolio` + `FolioNotCompletableException` → 409. Proofs: `FolioCompletionApiTest` (happy path; C1 straggler; C2 owes≠0; refunded-but-settled completes; idempotent 200 vs CANCELLED 409; 428 no-auth). Unblocks Stage 6 finished+settled reads. |
| RX-002 (`refactor-x/RX-002-line-amount-strategy-owned.md`) | D1 (`SCH-022 chk_line_amount` relaxed) | **FROZEN** | (this commit) | — | Relaxes SCH-022 `chk_line_amount` from `line_amount = unit_price * quantity` to the floor `line_amount > 0 AND line_amount >= unit_price * quantity`, so `line_amount` is strategy-owned (ROOM × nights). Append-only; revise only via RX-003. Pairs with `KNOWN_LIMITATION_ROOM_PRICING.md`. |
| `WAVE0_01_SCHEMA.sql` (via RX-002) | SCH-022 `chk_line_amount` (relaxed) | **FROZEN** · DONE | (this commit) | — | Ships as additive Flyway `V4__line_amount_strategy_owned.sql` (drop equality, add floor). SCH-022's §6 verification log is not edited; pointer banner added beside the `CHECK`. Proven by `BookingEntityTest.SCH_022_line_amount_check_rejects_below_floor_amount` + `BookingFlowApiTest` (2-night room persists `line_amount = rate × nights`). |
| `KNOWN_LIMITATION_ROOM_PRICING.md` + `VerticalStrategy.calculateLineAmount` | ROOM line-pricing correction | **RECORD + DONE** | (this commit) | — | Resolved-defect correction (not a deliberate limitation): ROOM line debt = `unit_price × quantity × nights` (calendar-date span; 0-night rejected). `unit_price` meaning unchanged. Additive `VerticalStrategy.calculateLineAmount` (Option 3) owns the per-vertical total; SPA stays `× quantity`. Enabled by RX-002 (SCH-022 relax). Proofs: `RoomStrategyTest` (3-night, multi-room, 0-night reject), `SpaStrategyTest` (no-nights), `BookingFlowApiTest` (end-to-end). |
| `FnbStrategy` (against ENM-001 / SCH-013) | F&B vertical strategy | **DONE** (no new freeze) | — | — | Not a freeze — `ENM-001` (`FNB` enum) and `SCH-013` (`product_fnb`) were frozen pre-existing; this row records implementation against them. `FnbStrategy` mirrors `SpaStrategy`: `vertical()` → `FNB`; `availableCapacity` = `coversCapacity` − reused `ProductRepository.countCommittedQuantity` (no new overlap SQL, caller-supplied window); `calculateUnitPrice` = `basePrice`; `calculateLineAmount` = `basePrice × quantity` (no nights factor — Rooms-only per `KNOWN_LIMITATION_ROOM_PRICING.md`); `defaultCaptureMode()` = `IMMEDIATE` (charter §6/§11). Registered via the `VerticalStrategyRegistration` marker. Out of scope (deferred like `spaAttributes`): `fnbAttributes` on the availability DTO; service-period-derived window; Events strategy. Evidence: `FnbStrategy` + `FnbStrategyTest` (7 cases, green under Testcontainers), commit `14cbf1b`. |
| F&B HTTP exercise (against API-004 / ENM-001) | F&B vertical wired-path exercise + OpenAPI prose refresh | **DONE** (no new freeze) | — | — | **Observability slice, not a freeze.** `FnbStrategy` (row above) was already HTTP-reachable through the generic `AvailabilityController` / `BookingService` (both dispatch via the registry — no F&B-specific endpoint exists or is wanted, charter §2); this row records the proof that the wired path works. (a) **F&B HTTP-exercise DONE** — `GET /availability?vertical=FNB` returns `coversCapacity` − committed overlap, and a line booked via the generic `POST /bookings/{id}/lines` (vertical derived from the product) drops `availableUnits` and is priced `base × quantity` (no nights factor); the availability row carries **neither** `roomAttributes` **nor** `spaAttributes` (`fnbAttributes` deferred — F&B did not inherit another vertical's block). Evidence: `FnbAvailabilityApiTest` (MockMvc + Testcontainers, Docker-guarded) + Postman folder `07 · F&B booking thread`. (b) **OpenAPI prose refresh** — three stale descriptive lines in the frozen `WAVE0_02_OPENAPI.yaml` ("ROOM and SPA exercised; FNB … reserved") refreshed to "ROOM, SPA, and FNB are exercised; EVENT reserved for a later slice." Text-only, arbiter-approved logged edit to a frozen artifact (no schema/enum/path/DTO change, no ID minted); **not a re-freeze** — the `FNB` enum value and all structure are untouched. |
| `WAVE0_01_SCHEMA.sql` (via V5) | SCH-061 `outbox_event.claimed_at` | **FROZEN** · DONE | (this commit) | — | Flag-2 crash recovery. Additive Flyway `V5__outbox_claimed_at.sql` adds a nullable `claimed_at TIMESTAMPTZ` (+ partial `idx_outbox_reclaim`), stamped in `claimEvent`/`reclaimStale`. Enables a second outbox pass that reclaims rows stranded in `PROCESSING` by a crash between claim-commit and handler-commit, re-dispatched through the idempotent handler (V2 unique indexes guard duplicate posts; already-posted replay → PROCESSED, not FAILED). Pre-V5 rows have `claimed_at = NULL` and are never reclaimed (accepted for POC). No existing column changes; SCH-060's §6 verification log untouched. Proofs: `OutboxReclaimTest` (stranded reclaim, no-double-post, fresh-not-reclaimed); regression `OutboxIdempotencyTest` + `LedgerCorrectnessTest` unchanged. |
| RX-003 (`refactor-x/RX-003-balance-definition-split.md`) | D1/D2/D3/D4 (`balance` split → `customerOwes` + `netRevenue`) | **FROZEN** | (this commit) | — | Splits the overloaded `balance = total − paid + refunded` (SCH-021 view + INV-004 getter + `FolioResponse.balance`) into `customerOwes = max(0, total − paid)` (settlement predicate; sole C2 gate input for folio completion) and `netRevenue = paid − refunded` (finance read). Fixes the refund case where the old formula falsely read a customer receivable (paid 600, refund 100 → old balance +100, should be owes 0). Read-model only — no capture/refund/posting change. Append-only; revise only via RX-004. Pairs with `DESIGN_FOLIO_COMPLETION.md`. |
| `WAVE0_01_SCHEMA.sql` (via RX-003) | SCH-021 `booking_balance` (split) + INV-004 getter | **FROZEN · DONE** | (this commit) | — | Ships as additive Flyway `V6__balance_split.sql` (drop `balance` column; add `customer_owes = GREATEST(0, total_amount − amount_paid)`, `net_revenue = amount_paid − amount_refunded`). SCH-021's §6 verification log is not edited; pointer banner added beside the view. INV-004 `getBalance()` → `getCustomerOwes()` / `getNetRevenue()` (removed, not aliased); `FolioResponse.balance` → two fields; `BookingRepository.findUnpaid()` predicate corrected to `customerOwes > 0`. Proven by `PaymentApiTest.RX_003_paidThenRefunded_customerOwesZero_netRevenueRetained` (HTTP money loop: 600 pay → 100 refund → `customerOwes == 0`, `netRevenue == 500`) + `BookingEntityTest` (view split, fully-paid, refund-no-debt, `findUnpaid` excludes refunded). |
| Full-lifecycle Postman coverage (against API-009..012, API-014/015, WHK-007/008/009) | runnable collection exercises capture / complete / refund / cancel end-to-end | **DONE** (superseded — no new freeze) | — | Streamlined one-guest lifecycle collection rewrite (row below) — pointer-only | **Runnable-proof slice, not a freeze.** No contract or code change — extends `docs/runnable-postman-collection/hotel-sim-postman-collection.json` (folders 08–12, 00–07 untouched) to drive the already-shipped lifecycle endpoints over real HTTP + webhooks. capture/cancel/refund are `202`; the core-api action drives the PSP and `payments-sim` **self-emits** the settlement webhook off-thread (1C-a), with per-line `revenuePosted` landing via the outbox poller (~5s) — so each step calls the action, then **polls** the payment/folio (`setNextRequest` + ~1s) until the terminal state, never asserting on the `202`. (No `/v1/test` capture/cancel/refund trigger — the real endpoints self-emit, so a trigger would `409`; the `/v1/test` **authorise** trigger stays, standing in for the deferred pay-web.) Proves: room MANUAL capture flips the room line `revenuePosted 0 → 54000` (revenue on capture, not auth); F&B IMMEDIATE settle; `completeLine` (ungated) + `completeFolio` (INV-007 gated; idempotent re-complete → **200, not 409**; C1 ACTIVE-line → 409); partial refund drops `netRevenue` while `customerOwes` stays 0 (RX-003); cancel of a *fresh* uncaptured MANUAL auth posts **no** ledger row (INV-006). Negative re-probes pass-as-designed. Also fixed **pre-existing** folder 03/05 breakage (collection was already red): stale `f.balance` → `customerOwes` (field removed by RX-003/`V6`), and folder 05's `amountAuthorised == 54000` → **62000** (the documented D3 roll-up sums all payments' authorised, incl. captured). Evidence: folders 08–12 + commit; verified green with Newman against the four-service smoke stack on a fresh DB volume — **125 assertions, 0 failures**. |
| Streamlined one-guest lifecycle Postman collection (against API-001..015, WHK-006..009, PSP-013, `fnbAttributes` Slice A5) | runnable collection rewritten as a single-guest, single-folio, cross-vertical story to a COMPLETED folio + a 5-probe guardrail appendix | **DONE** (no new freeze) | — | — | **Runnable-proof slice, not a freeze.** No contract or code change — `docs/runnable-postman-collection/hotel-sim-postman-collection.json` rewritten wholesale (spine `00 · Lights on` → `08 · Close the tab`, then appendix `P1`–`P5`) so it reads as **one story**: **John Patel**, **one folio**, **three verticals** — Room (MANUAL hold authorised at `02`, captured at checkout `07`), Spa + F&B (IMMEDIATE, settled `05`/`06`) — taken to a **COMPLETED** folio at `08` where `netRevenue == sum of per-line `revenuePosted` == 71000` and `customerOwes == 0`, proving scoped per-line allocation on one tab. `04` asserts the now-FROZEN `fnbAttributes` (Slice A5) is populated on the FNB availability row (`servicePeriod=DINNER`, `seatingMinutes=120`, `coversCapacity=40`) with `roomAttributes`/`spaAttributes` null (cross-vertical null integrity). Async settlement pattern carried over unchanged: capture/cancel `202` → `payments-sim` **self-emits** the webhook off-thread → **poll** to terminal, assert only then (never on the `202`); the `/v1/test` **authorise** trigger is the sole trigger used (pay-web stand-in) — the real capture/cancel endpoints self-emit, so a `/v1/test` trigger for them would `409`. Appendix preserves the guardrail evidence: `P1` human-gate (repercussive write without `X-Human-Auth` → 4xx), `P2` `lineCoverage` ≠ amount → 400, `P3` premature `completeFolio` on a fresh ACTIVE-line folio → 409 (C1), `P4` idempotent re-complete of the spine folio → 200, `P5` cancel of a fresh uncaptured MANUAL auth posts **no** ledger row (INV-006, mirror of `07`). **Supersedes the prior multi-customer / folder-by-folder collection** (row above) — pointer-only; the prior collection's partial-refund/RX-003 probe is intentionally not carried, since the single-completed-folio spine cannot hold a refund without muddying the `08` `netRevenue == sum` assertion. Evidence: verified green with Newman against the four-service smoke stack on a fresh DB volume — **113 assertions, 0 failures**. |
| `WAVE0_02_OPENAPI.yaml` | API-016 `getRevenue` (charter §9) | **DRAFT** | — (awaiting Desk sign-off) | — | First frozen-bound contract for charter §9 `getRevenue`. Read-only, additive, **no schema change** — backed by existing `LedgerPosting` (vertical + `bookingLine` FK, SCH-050) and `sumByVertical`. `GET /reports/revenue?from&to` (both date-time, required) → `RevenueReport` = `{ window:{from,to}, currency, byVertical:[{vertical, gross, refundedTotal, net}], totals:{...} }`. Semantics: `gross` = Σ REVENUE postings; `refundedTotal` = **absolute value** of Σ REFUND_REVERSAL (≥ 0); **`net` = gross − refundedTotal is a derived identity, never a stored field**; window is **half-open [from, to)** on posting time; all amounts minor units; `groupBy` is **vertical only** (no day / capture-mode variants — charter §9 minimal). Removes the Stage-6 deferral note (line 1). Flagged in `WAVE0_02` (`# DRAFT (API-016)` path/schema comments + `x-requirements-reports` block, block-level `status: DRAFT`). **NOT self-frozen** — awaiting Desk's DRAFT→FROZEN sign-off; contract-only slice, zero code. |
| `WAVE0_02_OPENAPI.yaml` | API-017 `listUnpaidBookings` (charter §9) | **DRAFT** | — (awaiting Desk sign-off) | — | First frozen-bound contract for charter §9 `listUnpaidBookings`. Read-only, additive, **no schema change** — backed by existing `Booking` amount columns (SCH-020/021), `payment_line` (WHK-016), and per-line REVENUE postings (`LedgerPosting`, SCH-050). `GET /reports/unpaid-bookings` (no params, full list) → `UnpaidBookings` = `{ bookings:[{ bookingId, shopperReference, customerName, currency, totalAmount, amountPaid, customerOwes, lines:[{lineId, vertical, lineAmount, lineRevenuePosted, lineOwes, lineHeldAuth}] }] }`. Selection predicate: `total_amount > amount_paid` (SQL form of `customerOwes > 0`, RX-003). Per line: `lineRevenuePosted` = Σ REVENUE postings for the line; **`lineOwes` = lineAmount − lineRevenuePosted — CAPTURED ONLY, held auth does NOT reduce owes** (mirrors booking-level `customerOwes`, RX-003); **`lineHeldAuth` = Σ `payment_line.amount` where parent `payment.status = AUTHORISED`, informational only** (reflects committed DB state — a capture requested but not yet webhook-confirmed still shows as held: **eventual-consistency caveat**; does not affect `lineOwes`). All amounts minor units. Flagged in `WAVE0_02` (`# DRAFT (API-017)` path/schema comments + `x-requirements-reports` block). **NOT self-frozen** — awaiting Desk's DRAFT→FROZEN sign-off; contract-only slice, zero code. |
| `WAVE0_AUDIT.md` | — | RECORD (not a contract) | `ab83274` | — | Rationale for the Stage 2 contracts + the GAP-1/2 fixes; never "freezes". |

> **FROZEN ≠ DONE.** An artifact being **FROZEN** (its contract is fixed) is separate from
> its IDs being **DONE** (implemented + tested). Stage 2 here is FROZEN-but-not-built: the
> contract is locked, the code is the next step, and GAP-1/GAP-2 (see audit) are fixed *as
> part of* that build. Per-artifact verification logs track DONE per ID; this ledger tracks
> freeze only.

---

## 1c. Reality note — Stage 1 already shipped

The Wave 0 gate in §5 reads as if all parallel work waits for the full set. In practice the
**Stage 1 vertical slice** (customer + room booking, no money) was built and merged against
the frozen `01`/`02-Stage1`/`04` artifacts before the payment contracts existed — consistent
with the freeze rule for those artifacts. The lapse not to repeat: payment/ledger
*schema-backed code* was also added early, ahead of any `WHK-` contract (see `WAVE0_AUDIT.md`).
The §1b ledger + the `CLAUDE.md` gate exist so no further code precedes a `FROZEN` row here.

---

## 2. The mental model an implementing agent must hold

- **`core-api` is the entire system.** All logic, all rules, all state. `ops-web` and the
  future MCP are two thin heads on this one body.
- **Every endpoint must fully serve `ops-web` as a standalone console.** The MCP is a thin
  additive wrapper over the same surface. **No capability may exist solely for the agent.**
  If a brief tempts you to add an "agent-only" endpoint, that is a smell — flag it.
- **DTOs at every boundary.** Entities are never serialised directly. The OpenAPI DTOs are
  the contract; the DDL is internal to `core-api`.
- **Writes revalidate at write time.** Every write re-checks availability and price
  atomically; if state moved since the caller last read, it fails loudly (`409`) with
  current state rather than writing stale data. This is the core safety mechanism.
- **Repercussive writes are human-gated server-side.** `core-api` requires a
  human-authorisation signal the caller cannot self-mint. (Mechanism detailed in
  `02_OPENAPI`.) Confirmation is dual-channel: an `ops-web` click and an agent "yes" are
  equally valid.
- **Ledger posts on capture, not auth.** Authorisation is a hold, not a financial event.

---

## 3. The freeze rule

After sign-off, the five artifacts are **frozen**. While frozen:

- **No agent edits a contract.** Not the SQL, not the YAML, not the webhook payloads.
- Agents build their package *against* the contracts, mocking everything they don't own.
- "Done" for a package means: it implements its contract slice **and** has a test that
  asserts against the contract (schema shape / endpoint shape / webhook payload shape).

The freeze is what makes parallelism safe. An agent that silently "improves" a DTO breaks
every other agent depending on it, days later, invisibly.

---

## 4. The contract-change protocol

Contracts will need changes — that's expected. The discipline is *how*:

1. **Flag, don't fix.** The agent that hits a problem files a flagged question to the
   central arbiter (you). It describes the problem and the proposed change. It does **not**
   edit the contract or route around it locally.
2. **Arbitrate centrally.** The arbiter decides, edits the contract **once**, bumps a
   version note at the top of the affected file, and records the change in that file's
   changelog section.
3. **Propagate.** The arbiter notifies every package whose brief references the changed
   slice. Affected agents re-sync to the new contract.

One rule prevents ~90% of parallel-agent chaos: **never modify a contract to unblock
yourself; flag it.**

---

## 5. Definition of done for Wave 0 itself (the gate)

Wave 0 is complete — and Wave 1 may begin — only when **all** of:

- [ ] `01_SCHEMA.sql` applies cleanly to a fresh Postgres and is signed off.
- [ ] `02_OPENAPI.yaml` validates and covers every read + write in the capability surface.
- [ ] `03_WEBHOOK_PSP_CONTRACT.md` fully specifies every PSP event + the consumer rules.
- [ ] `04_SCAFFOLD.md` brings up all services via docker-compose with green health checks
      and an empty-but-wired skeleton (no business logic yet).
- [ ] Enums/glossary in `01` are the sole source of allowed values; nothing contradicts it.

Until every box is ticked, **nothing parallel starts.** This is the gate.

---

## 6. Wave 1 packages (for context; briefs come after the gate)

- **A.** Core domain + persistence (publishes domain interfaces day one)
- **B.** Vertical strategies (Rooms / Spa / F&B / Events) — availability, pricing, capture default
- **C.** Ledger / finance (consumes booking events via outbox)
- **D.** Payment orchestration in `core-api` (links, webhooks, idempotency)
- **E.** `payments-sim` + `pay-web`
- **F.** `ops-web` — a complete console, built against the OpenAPI mock

> ⚠️ **Package E above is superseded in part by [RX-001](refactor-x/RX-001-psp-direction-and-statefulness.md)** (see Freeze Ledger §1b). Original text preserved; do not build against the superseded portion without checking the ledger.

One **integration owner** holds `docker-compose` + the end-to-end smoke test throughout.

---

## 7. Changelog

| Version | Date | Change |
|---------|------|--------|
| 0.1 | (draft) | Initial Wave 0 set drafted for sign-off. |
| 0.2 | (prior commit) | Added §1b Freeze Ledger (single source of truth) + §1c reality note. Recorded 01 / 02-Stage1 / 04 FROZEN (built-against); froze 02-Stage2 + 03. |
| 0.3 | 2026-06-12 | §1b: added **Superseded-by** column; populated with RX-001 on the affected rows (`pay-web` framing in `WAVE0_03`; SCF-003's forward spec). Added rows for RX-001 (FROZEN) and SCF-005 (FROZEN, born in RX-001). Registered `WAVE0_05_PSP_API.md` as **DRAFT**. Added pointer banners (no edits to requirement text or verification logs) in §1 consumer cell and §6 Wave 1 packages. |
| 0.4 | 2026-06-12 | Froze `WAVE0_05_PSP_API.md` (PSP-001..017): §1b row DRAFT→FROZEN, ID range pinned. Corrected the outbound callback path `/v1/payments/webhooks` → `/webhooks/psp` (3 refs in WAVE0_05 §1/§2/§7.2) to match the shipped `WebhookController` (API-013) before freeze. No requirement semantics changed; path correction only. |
| 0.5 | 2026-06-13 | §1b `WAVE0_05` row: noted **IN-BUILD** — Feature 2 Part A (1C-a) DONE (PSP-011/013/015/016, +PSP-005 mint-on-event, +PSP-008 reaffirmed); per-ID proofs recorded in `WAVE0_05 §9a`. Freeze status unchanged (still FROZEN at v0.4); this row records build progress only, per the freeze-vs-done split. |
| 0.6 | 2026-06-13 | §1b `WAVE0_05` row: Feature 2 Part B (1C-b) DONE — PSP-001..004 outbound client (`PspGateway`), PSP-006 tx-ordering (`PaymentOrchestrator`; class-level `@Transactional` removed from `PaymentService`), PSP-007 fail-loud 502, and PSP-017 (four-service `docker compose` up healthy + live money-loop smoke via `docker-compose.smoke.yml`). PSP-001..017 all DONE; per-ID proofs in `WAVE0_05 §9a`. Freeze unchanged. |
| 0.7 | 2026-06-13 | §1b: added API-004 widening row (Slice A3). API-004 re-frozen to vertical-agnostic; three "ROOM only" text locations updated in `WAVE0_02_OPENAPI.yaml`. `SpaAvailabilityApiTest` proves SPA availability over HTTP end-to-end (Testcontainers). Gate A closed. |
| 0.9 | 2026-06-14 | §1b: added **API-008 `lineCoverage` (Slice S2, DRAFT)** row — exposes WHK-016 over HTTP via an additive optional `lineCoverage` array on `PaymentLinkCreateRequest`, plus a derived read-only `revenuePosted` on `BookingLineResponse`. Backward-compatible (omitted = folio-wide, WHK-012). API-008's FROZEN status is unchanged; the amendment is flagged in `WAVE0_02` (`x-requirements-stage2` API-008 `amendment` block) and awaits Desk sign-off — not self-frozen. |
| 0.8 | 2026-06-14 | §1b: added **WHK-016 (DRAFT)** row — scoped payment→line allocation (Stage 4 Slice 1), additive over WHK-012; new `payment_line` table via additive `V3` migration. Drafted in `WAVE0_03 §5.1`. `WAVE0_02` API-008 **request** DTO unchanged (scoping wired at the service layer; HTTP exposure deferred to a separate API-contract change). One **additive, backward-compatible** read field — `FolioResponse.amountAuthorised` (D3 live "secured" roll-up, visible-only) — added to `WAVE0_02` FolioResponse (API-005/006/007) + additive `booking.amount_authorised` column in `V3`. WHK-016 not frozen — awaiting Desk sign-off. |
| 1.0 | 2026-06-15 | §1b: added **API-004 `spaAttributes` (Slice A4, FROZEN)** row — additive, backward-compatible nullable `spaAttributes` object on `AvailabilityResult` (treatmentKind, durationMinutes, therapistGender, concurrentSlots), mirroring `roomAttributes` for ROOM. Populated for SPA, null for other verticals; `roomAttributes` stays null for SPA; existing ROOM responses byte-identical. Frozen on Desk sign-off (amendment block + schema comment flipped DRAFT→FROZEN in `WAVE0_02`). Unblocks charter §5 therapist-gender preference matching. Implementation + proof: `SpaAvailabilityApiTest`. |
| 1.1 | 2026-06-15 | §1b: froze **RX-002** and recorded the ROOM line-pricing correction. SCH-022 `chk_line_amount` relaxed from `line_amount = unit_price * quantity` to the floor `line_amount > 0 AND line_amount >= unit_price * quantity` (additive Flyway `V4`), so `line_amount` is strategy-owned — ROOM line debt = `unit_price × quantity × nights` (calendar-date span; 0-night rejected) via the additive `VerticalStrategy.calculateLineAmount` (Option 3); SPA unchanged. `unit_price` meaning unchanged. Set the all-SCH row's `Superseded-by` → RX-002 (scoped to `chk_line_amount`); added pointer banner beside the `CHECK` in `WAVE0_01_SCHEMA.sql`; SCH-022 verification log untouched. Pairs with `KNOWN_LIMITATION_ROOM_PRICING.md`. Proofs: `RoomStrategyTest`, `SpaStrategyTest`, `BookingFlowApiTest`, `BookingEntityTest`. |
| 1.2 | 2026-06-17 | §1b: froze **WHK-016** and **API-008 `lineCoverage` (Slice S2)** post-implementation (§1c reconciliation) — both DRAFT→**FROZEN · DONE**, citing bb9395c/af8c42e (WHK-016) and 7674e63/c2ea71f (API-008 amendment); runnable Postman proof `9e7476e`. Flipped the matching DRAFT markers in `WAVE0_03 §5.1` and `WAVE0_02` (revenuePosted/lineCoverage comments + API-008 amendment sub-status); base `x-requirements-stage2: FROZEN` unchanged. No code, no ID re-minting, no other ledger row touched. |
| 1.7 | 2026-06-29 | §1b: added **`FnbStrategy` (DONE, no new freeze)** row — F&B vertical strategy implemented against the pre-frozen `ENM-001` (`FNB`) + `SCH-013` (`product_fnb`); not a freeze. Mirrors `SpaStrategy`: `coversCapacity` − reused `countCommittedQuantity` (no new overlap SQL), `unitPrice` = `basePrice`, `lineAmount` = `basePrice × quantity` (no nights factor), `defaultCaptureMode()` = `IMMEDIATE` (charter §6/§11); registered via the `VerticalStrategyRegistration` marker. Deferred (like `spaAttributes`): `fnbAttributes` DTO block, service-period window, Events strategy. Proofs: `FnbStrategyTest` (7 cases, green under Testcontainers). Seed F&B product (`5555…`, DINNER, 40 covers) added to the Postman seed SQL. No contract-text edits. |
| 1.6 | 2026-06-18 | §1b: flipped **API-014** + **API-015** rows to **FROZEN · DONE** — Slice 2 implemented `completeLine` (ungated, ACTIVE→COMPLETED, no folio side effect) + `completeFolio` (INV-007 gated, CONFIRMED→COMPLETED; C1 all-lines-COMPLETED + C2 `customerOwes==0`; 409 `FolioNotCompletable` names stragglers + live owes; idempotent 200; CANCELLED/PENDING → 409). `completeFolio` reads server-maintained totals fresh in-tx and deliberately skips the ACTIVE-only `recalculateTotals`. Proofs: `FolioCompletionApiTest` (10 cases). Full core-api suite 147/147. Freeze unchanged (v1.5). |
| 1.5 | 2026-06-18 | §1b: froze **API-014 completeLine** + **API-015 completeFolio** (Stage 2 close-out / Slice 2) — additive folio-completion HTTP surface over `WAVE0_02`. New paths `POST /bookings/{id}/lines/{lineId}/complete` (ungated, ENM-003) and `POST /bookings/{id}/complete` (INV-007 gated, ENM-002; write-time C1+C2 revalidation, C2 = RX-003 `customerOwes`; 409 `FolioNotCompletable`). Applied to `WAVE0_02`: §A paths, §B `LineId` param, §C `FolioNotCompletable` response, §D `FolioCompletionState`/`FolioCompletionConflict` schemas, §E API-014/015 `x-requirements-stage2` entries. PENDING-folio → 409 and the nested line-complete path ratified at freeze. Drafted in `PROPOSAL_API_014_015_FOLIO_COMPLETION.md`; pairs with `DESIGN_FOLIO_COMPLETION.md`. Implementation pending (Slice 2). |
| 1.4 | 2026-06-18 | §1b: flipped the **SCH-021-via-RX-003** row to **FROZEN · DONE** — Slice 1 implemented the `balance` → `customerOwes` + `netRevenue` split. Additive Flyway `V6__balance_split.sql` (drop/recreate `booking_balance` view); `Booking.getBalance()` removed (not aliased) → `getCustomerOwes()`/`getNetRevenue()`; `FolioResponse` two fields; `BookingRepository.findUnpaid()` predicate corrected to `customerOwes > 0` (was outside RX-003 §4's stated blast radius). Read-model only; no capture/refund/posting change. Proofs: `PaymentApiTest.RX_003_paidThenRefunded_customerOwesZero_netRevenueRetained`, `BookingEntityTest`. No contract-text or verification-log edits; frozen `WAVE0_02` keeps `balance` + its RX-003 banner. |
| 1.3 | 2026-06-17 | §1b: added **SCH-061 `outbox_event.claimed_at` (FROZEN · DONE)** row — Flag-2 stale-`PROCESSING` outbox reclaim. Additive Flyway `V5__outbox_claimed_at.sql` (nullable `claimed_at` + partial `idx_outbox_reclaim`); a second `OutboxProcessor` pass reclaims rows stranded in `PROCESSING` by a crash between claim-commit and handler-commit, re-dispatched through the idempotent `OutboxEventHandler` (V2 unique indexes guard double-posts; already-posted replay → PROCESSED, not FAILED). `reclaim-after` cutoff configurable (`outbox.reclaim-after`, default `PT2M`). No existing column changes; SCH-060 verification log untouched. Proofs: `OutboxReclaimTest`; regression `OutboxIdempotencyTest`/`LedgerCorrectnessTest` byte-identical. |
| 1.9 | 2026-06-29 | §1b: added **Full-lifecycle Postman coverage (DONE — no new freeze)** row — the runnable collection (`hotel-sim-postman-collection.json`) extended with folders 08–12 (00–07 untouched) to drive the already-shipped capture / complete / refund / cancel endpoints (API-009..012, API-014/015) end-to-end over real HTTP + webhooks. Settlement pattern: core-api action (`202`) → `payments-sim` self-emits the webhook off-thread (1C-a) → **poll** the payment/folio until terminal (no `/v1/test` capture/cancel/refund trigger — the real endpoints self-emit, so a trigger would 409; the authorise trigger stays). Proves capture-posts-revenue, idempotent re-complete (200 not 409), partial refund (netRevenue drops, customerOwes unchanged — RX-003), and cancel-of-uncaptured-auth posts nothing (INV-006); negative probes pass-as-designed. Also fixed **pre-existing** folder 03/05 breakage: stale `f.balance` → `customerOwes` (removed by RX-003/`V6`) and folder 05 `amountAuthorised` `54000` → `62000` (documented D3 roll-up sums all payments' authorised). No contract/ID/code change — runnable-proof slice only. Evidence: folders 08–12 + commit; verified green with Newman (125 assertions, 0 failures) on the four-service smoke stack. |
| 1.8 | 2026-06-29 | §1b: added **F&B HTTP exercise (against API-004 / ENM-001, DONE — no new freeze)** row — the already-shipped `FnbStrategy` proven HTTP-reachable end-to-end through the generic `AvailabilityController` / `BookingService` (registry dispatch; no F&B-specific endpoint, charter §2). Evidence: `FnbAvailabilityApiTest` (MockMvc + Testcontainers, Docker-guarded — availability = covers − overlap, generic line booking drops `availableUnits`, line priced `base × quantity` no nights, row carries neither `roomAttributes` nor `spaAttributes`) + Postman folder `07 · F&B booking thread`. Same row logs the **OpenAPI prose refresh**: three stale descriptive lines in the frozen `WAVE0_02_OPENAPI.yaml` updated "ROOM and SPA … FNB reserved" → "ROOM, SPA, and FNB are exercised; EVENT reserved for a later slice." Text-only, arbiter-approved logged edit (no schema/enum/path/DTO change, no ID minted); **not a re-freeze** — `FNB` enum value + structure untouched. |
| 2.0 | 2026-06-29 | §1b: added **API-004 `fnbAttributes` (Slice A5, DRAFT)** row — additive, backward-compatible nullable `fnbAttributes` object on `AvailabilityResult` (servicePeriod, seatingMinutes, coversCapacity — raw `product_fnb` config, no schema migration), mirroring `spaAttributes` for SPA / `roomAttributes` for ROOM. Populated for FNB, null for other verticals; `roomAttributes`/`spaAttributes` stay null for FNB; existing ROOM/SPA responses byte-identical. Closes the `fnbAttributes` deferral on the F&B rows. Flagged in `WAVE0_02` (schema `# DRAFT AMENDMENT (Slice A5)` comment + `x-requirements-stage2` API-004 `amendmentA5` sibling key; A3/A4 blocks untouched; added `SCH-013` to API-004 `mapsTo`). **NOT self-frozen** — awaiting Desk's DRAFT→FROZEN sign-off. Implementation + proof: `FnbAvailabilityApiTest` (fnbAttributes populated; cross-vertical null integrity). |
| 2.1 | 2026-06-30 | §1b: **API-004 `fnbAttributes` (Slice A5)** flipped **DRAFT→FROZEN** on Desk sign-off. Additive, backward-compatible; no code change — the field shipped in PR #36, this is the contract authorisation. Schema comment (`# DRAFT AMENDMENT (Slice A5)` → `# AMENDMENT (Slice A5) FROZEN on Desk sign-off`), `x-requirements-stage2` API-004 `amendmentA5` status, and the §1b row all flipped to FROZEN (trailing "NOT FROZEN" sentences struck). A3/A4 blocks untouched. |
| 2.3 | 2026-06-30 | §1b: added **two DRAFT rows** — **API-016 `getRevenue`** (`GET /reports/revenue?from&to` → `RevenueReport` by vertical; `net = gross − refundedTotal` derived identity, `refundedTotal` = \|Σ REFUND_REVERSAL\|, half-open `[from, to)` posting-time window, `groupBy` vertical only) and **API-017 `listUnpaidBookings`** (`GET /reports/unpaid-bookings` → `UnpaidBookings`; predicate `total_amount > amount_paid`; captured-only `lineOwes`, AUTHORISED-only informational `lineHeldAuth` with the eventual-consistency caveat). Both read-only, additive, **no schema change** — backed by existing `LedgerPosting`/`sumByVertical`, `payment_line`, and `Booking` amount columns. Minted in `WAVE0_02_OPENAPI.yaml` as two new paths + six schemas + a new `x-requirements-reports` block (block-level `status: DRAFT`), all marked `# DRAFT`; the line-1 `getRevenue deferred to Stage 6` note removed. Contract-only slice (zero `.java`). **NOT self-frozen** — DRAFT→FROZEN is Desk's sign-off. |
| 2.2 | 2026-06-30 | §1b: added **Streamlined one-guest lifecycle Postman collection (DONE — no new freeze)** row and pointed the prior **Full-lifecycle Postman coverage** row's `Superseded-by` at it (pointer-only). The runnable collection (`hotel-sim-postman-collection.json`) was rewritten wholesale from the multi-customer, folder-by-folder shape (which read like a changelog — two customers, no single cross-vertical folio, folios left incomplete) into **one story**: John Patel, one folio, three verticals (Room MANUAL→captured, Spa + F&B IMMEDIATE) taken to a COMPLETED folio with `netRevenue == sum of per-line revenue == 71000`, `customerOwes == 0`. Spine `00`→`08` + guardrail appendix `P1`–`P5`. Step `04` shows the now-FROZEN `fnbAttributes` (Slice A5) on the FNB availability row with cross-vertical null integrity. Async settlement pattern unchanged (self-emit → poll; authorise is the only `/v1/test` trigger). Partial-refund/RX-003 probe intentionally dropped — incompatible with the single-completed-folio spine. No contract / ID / code change — runnable-proof slice only; the prior collection's UUIDs were not preserved (nothing references them — run by file path via Newman). Evidence: verified green with Newman against the four-service smoke stack on a fresh DB volume — **113 assertions, 0 failures**. |
