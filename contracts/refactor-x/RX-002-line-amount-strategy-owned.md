# RX-002 — `line_amount` is strategy-owned (SCH-022 `chk_line_amount` relaxed)

> **Refactor record (append-only).** This is not a new contract; it is a delta against an
> existing frozen contract. It records one decision that turned during the ROOM line-pricing
> correction, names the frozen statement it supersedes, and pairs with the decision note
> `contracts/KNOWN_LIMITATION_ROOM_PRICING.md`.
>
> **The Freeze Ledger (`WAVE0_00 §1b`) is the authoritative index.** A reader landing on the
> superseded statement in isolation must consult §1b's `Superseded-by` column before building
> against it. The pointer banner on the superseded statement says *only* that — it does not
> restate the change. The "what changed" lives here.
>
> **Self-rule (append-only).** RX-002 is itself frozen on commit. It is revised only by a
> superseding `RX-003`, never edited in place. Same discipline as the `WAVE0_0X` contracts
> and RX-001.
>
> **Status:** `FROZEN` on commit of this file (authoritative in `WAVE0_00 §1b`).
> **Owner / arbiter:** Desk.
> **Depends on:** `WAVE0_01_SCHEMA.sql` (SCH-022), `WAVE0_00_OVERVIEW.md` §1b/§4,
> `contracts/KNOWN_LIMITATION_ROOM_PRICING.md` (the pricing decision this enables).

---

## 1. Context

`contracts/KNOWN_LIMITATION_ROOM_PRICING.md` corrects a latent ROOM under-pricing defect:
a multi-night room line's debt is `unit_price × quantity × nights`, not `unit_price ×
quantity`. The per-night rate (`unit_price`) keeps its meaning; only `line_amount` — the
total line debt — is corrected, and ownership of that total moves into the vertical strategy
(`VerticalStrategy.calculateLineAmount`, additive; Option 3).

That correction collides with a **frozen schema invariant** that the decision note did not
account for: SCH-022's `chk_line_amount CHECK (line_amount = unit_price * quantity)`. Any
stay longer than one night makes `line_amount ≠ unit_price * quantity`, so the database
rejects the corrected debt. The invariant must turn for the correction to be persistable.

Per `WAVE0_00 §4` and the append-only discipline, the delta is recorded here rather than by
editing the frozen `WAVE0_01_SCHEMA.sql` in place. The schema's verification log (which
records what Stage 1 shipped) is untouched.

---

## 2. Decision

### D1 — `line_amount` is strategy-owned; `chk_line_amount` is relaxed to a floor.

The exact equality `line_amount = unit_price * quantity` no longer holds for duration-priced
verticals. It is replaced by a vertical-agnostic invariant:

> `CHECK (line_amount > 0 AND line_amount >= unit_price * quantity)`

- **ROOM:** `line_amount = unit_price × quantity × nights`, with `nights ≥ 1` (a 0-night line
  is rejected at the strategy, not the DB). The floor holds (`× nights ≥ × 1`).
- **Verticals with no duration dimension (SPA, and F&B/Events when built):**
  `line_amount = unit_price × quantity` — the floor holds as an equality, so their behaviour
  is unchanged.

**Rationale.** A single arithmetic `CHECK` cannot branch on vertical, so it cannot encode
"rooms multiply by nights, others don't" as an equality without leaking room-specific date
math onto every vertical (a same-day spa line would be forced to `× 1` via a date span, and a
multi-day spa line wrongly forced to `× span`). The honest generic invariant is therefore a
**positive, no-under-count floor**: `line_amount` is never less than one period's worth and
never non-positive. The precise per-vertical total is owned and proven at the strategy layer
(`RoomStrategyTest`, `SpaStrategyTest`) and end-to-end (`BookingFlowApiTest`), where the
business rule actually lives — not in the DDL.

`unit_price` is **not** changed in meaning (that would be the very class of bug this
correction fixes — a field meaning something other than its name). It remains the per-unit
rate, snapshotted to `booking_line.unit_price` and returned by
`VerticalStrategy.calculateUnitPrice` for the availability screen.

---

## 3. Supersedes (ID-level, with quoted source)

The original is **preserved verbatim** in its file; a pointer-only banner is added beside it
pointing here and to the Freeze Ledger. The SCH-022 verification log in `WAVE0_01` is **not**
touched.

### 3.1 `WAVE0_01_SCHEMA.sql` — SCH-022 `chk_line_amount` (forward-spec only)

Quoted source (`WAVE0_01_SCHEMA.sql`, `booking_line` table):

> *"`CONSTRAINT chk_line_amount     CHECK (line_amount = unit_price * quantity)`"*

And the column comment:

> *"`line_amount   BIGINT NOT NULL,                           -- unit_price * quantity (snapshot)`"*

**Superseded as a forward spec only.** SCH-022's verification record (`BookingEntityTest`)
remains valid for what it asserted at Stage 1. The replacement forward invariant ships as
Flyway migration **`V4__line_amount_strategy_owned.sql`** (additive `ALTER`: drop + re-add),
which `core-api` applies on top of `V1`. The rest of SCH-022 (`chk_line_qty`,
`chk_line_window`, indexes, columns) is unchanged.

### 3.2 What is **not** superseded

- **`unit_price` semantics** — unchanged. Per-unit rate; still snapshotted; still the
  availability quote.
- **WHK-012 / WHK-016 allocation** — unchanged in substance. They allocate a
  captured/refunded amount across booking lines; they read `line_amount` as the per-line
  debt, which is exactly what is now correctly larger for multi-night rooms. The
  fill-by-line-order and scoped-coverage rules are untouched.
- **`chk_line_qty`, `chk_line_window`** and all other `booking_line` constraints/indexes.
- **All Stage 1 verification records**, including SCH-022's. They record history.

---

## 4. Does NOT touch (guards)

- **Does not edit any verification log in any artifact.** SCH-022's `BookingEntityTest`
  entry stands. (The test body is updated to assert the *new* floor invariant — a test of the
  current spec — but the ledger's historical verification row is not rewritten.)
- **Does not edit the text or acceptance criteria of SCH-022 in `WAVE0_01_SCHEMA.sql`.** Only
  a pointer-only banner is added beside the superseded `CHECK` and column comment.
- **Does not fold `nights` into `unit_price`.** `unit_price` stays the per-unit rate.
- **Does not rewrite `V1`.** The change is a new additive `V4` migration; `V1`/`V2`/`V3` are
  immutable.
- **Does not self-freeze.** Freezing is the arbiter's act, recorded in the Freeze Ledger.

---

## 5. Accountability

| Field | Value |
|-------|-------|
| Owner | Desk (arbiter) |
| Status | `FROZEN` on commit of this file (authoritative in `WAVE0_00 §1b`). |
| Sign-off | ☑ frozen as RX-002 — supersedence in force; revision requires `RX-003`. |
| Consumers | `core-api` domain/persistence (booking line writes), every Wave 0 reader (must check §1b `Superseded-by` on SCH-022). |

### 5a. Verification log

| ID | Built | Commit/PR | Proving test |
|----|-------|-----------|--------------|
| SCH-022 (relaxed `chk_line_amount`) | `V4__line_amount_strategy_owned.sql` drops the equality, adds `line_amount > 0 AND line_amount >= unit_price * quantity`. | (this branch) | `BookingEntityTest.SCH_022_line_amount_check_rejects_below_floor_amount` (DB rejects below-floor); `BookingFlowApiTest` (2-night room persists `line_amount = rate × nights` through the relaxed constraint). |

---

## 6. Changelog

| Version | Date | Change |
|---------|------|--------|
| 0.1 | 2026-06-15 | Initial draft & freeze of RX-002 — relaxes SCH-022 `chk_line_amount` from the exact equality to a positive no-under-count floor, so `line_amount` can be strategy-owned (ROOM `× nights`). Pairs with `KNOWN_LIMITATION_ROOM_PRICING.md`. Ships as additive Flyway `V4`. Names the superseded `CHECK` + column comment with verbatim quotes; forward-points to the migration and the proving tests. |
