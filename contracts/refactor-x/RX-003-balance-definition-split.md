# RX-003 — `balance` split into `customerOwes` + `netRevenue` (SCH-021 / INV-004 corrected)

> **Refactor record (append-only).** This is not a new contract; it is a delta against two
> existing frozen statements. It records one definitional defect found while scoping the
> folio-completion lifecycle (Stage 2 close-out), names the frozen statements it supersedes,
> and is paired with the design note `contracts/DESIGN_FOLIO_COMPLETION.md`.
>
> **The Freeze Ledger (`WAVE0_00 §1b`) is the authoritative index.** A reader landing on the
> superseded statement (SCH-021 view, or the INV-004 balance getter) in isolation must consult
> §1b's `Superseded-by` column before building against it. The pointer banner on the superseded
> statement says *only* that — it does not restate the change. The "what changed" lives here.
>
> **Self-rule (append-only).** RX-003 is itself frozen on commit. It is revised only by a
> superseding `RX-004`, never edited in place. Same discipline as the `WAVE0_0X` contracts,
> RX-001, and RX-002.
>
> **Status:** `FROZEN` on commit of this file (authoritative in `WAVE0_00 §1b`).
> **Owner / arbiter:** Desk.
> **Depends on:** `WAVE0_01_SCHEMA.sql` (SCH-021 `booking_balance` view), `WAVE0_00_OVERVIEW.md`
> §1b/§4, `WAVE0_02_OPENAPI.yaml` (`FolioResponse.balance`). Enables `DESIGN_FOLIO_COMPLETION.md`.

---

## 1. Context

The Stage-2-close folio-completion work needs a single, honest predicate for "is this folio
settled?" — the gate input for `completeFolio`. Scoping that predicate surfaced a latent
defect in the **frozen** balance definition that Stage 1 shipped and Stage 2 never revisited.

`balance` is defined identically in two frozen places:

- **SCH-021** — the `booking_balance` SQL view:
  `(total_amount - amount_paid + amount_refunded) AS balance`.
- **INV-004** — the Java getter `Booking.getBalance()`:
  `return totalAmount - amountPaid + amountRefunded;`

Both encode `balance = total - paid + refunded`. The contract documents this single number as
the "Paid == (balance == 0)" signal (`Booking` javadoc, `FolioResponse` javadoc).

### The defect

`amount_paid` is **gross captured** (`Σ payment.amount_captured`); a refund posts a separate
reversal and does **not** reduce `amount_paid`. `amount_refunded` is **gross refunded**
(`Σ settled refund`). Walk a goodwill-refund scenario through the frozen formula:

| Scenario                  | total | paid | refunded | frozen `balance` | correct receivable |
|---------------------------|-------|------|----------|------------------|--------------------|
| Booked, unpaid            | 600   | 0    | 0        | **+600**         | +600 ✓             |
| Booked, fully paid        | 600   | 600  | 0        | **0**            | 0 ✓                |
| Paid, then £100 refunded  | 600   | 600  | 100      | **+100**         | **0** ✗            |

The third row is wrong. After a £100 refund the customer owes **nothing** — the hotel handed
£100 *back*. The frozen formula reads `+100`, i.e. "customer still owes £100", which would
*block folio completion* and misreport a receivable. The `+ amount_refunded` term silently
re-opens a debt that a refund did not create.

### Root cause

One field is doing two incompatible jobs. The formula's *intent* was reaching for **net
revenue retained** (`paid - refunded`, which correctly falls 600 → 500 after the refund), but
it is **named and surfaced** as `balance` — a customer receivable. Two distinct financial
concepts wearing one name. Settlement (does the customer owe anything?) and revenue (what did
the hotel keep?) are not the same number and must not share a field.

Per `WAVE0_00 §4` and the append-only discipline, the delta is recorded here rather than by
editing the frozen `WAVE0_01_SCHEMA.sql` or the INV-004 statement in place.

---

## 2. Decision

### D1 — Split the one overloaded `balance` into two honestly-named, separately-derived fields.

- **`customerOwes`** `= max(0, total_amount - amount_paid)`
  The settlement predicate. "Fully settled" == `customerOwes == 0`. Refunds do **not** appear
  in it — a refund cannot make a customer owe more. `max(0, …)` because an over-capture or
  goodwill scenario must never render as a *negative* amount owed (that is the hotel's
  liability, tracked separately if ever needed; out of POC scope).

- **`netRevenue`** `= amount_paid - amount_refunded`
  The finance read. What the hotel has retained. Reportable, can be negative in pathological
  refund-exceeds-capture cases (which `RefundPayment` already forbids upstream, so in practice
  ≥ 0). This is a **read-model** number, never a gate input.

### D2 — `customerOwes == 0` is the sole settlement gate input.

The folio-completion gate (`DESIGN_FOLIO_COMPLETION.md`) keys on `customerOwes == 0` and
**nothing else** on the money side. No "balance is zero OR the non-zero is refund-attributable"
special case — that branch existed only to paper over D1's defect and is deleted by
construction. A refunded folio has `customerOwes == 0` and closes cleanly.

### D3 — `SCH-021` view and `INV-004` getter both turn.

- `booking_balance` view: replace the single `balance` column with `customer_owes` and
  `net_revenue` computed as in D1 (new Flyway migration; the view is dropped and recreated —
  Postgres requires `CREATE OR REPLACE VIEW` to keep column order, so a `DROP VIEW` +
  `CREATE VIEW` pair is used since the column set changes).
- `Booking.getBalance()` is replaced by `getCustomerOwes()` and `getNetRevenue()`. The old
  getter name is removed, not aliased — a stale `balance` reader must fail to compile, not
  silently bind to one of the two new numbers.

### D4 — `FolioResponse` (API-005/006/007) exposes both, drops `balance`.

`FolioResponse.balance` → `FolioResponse.customerOwes` + `FolioResponse.netRevenue`. This is a
**breaking response-shape change** to a frozen OpenAPI contract. `ops-web` is the only consumer;
it is updated in the same slice. Because no external client exists (POC), no deprecation window
is run — the field is renamed outright and the OpenAPI delta recorded in §1b.

---

## 3. Superseded statements (for §1b)

| Frozen statement | File | Superseded-by | Pointer banner to add |
|------------------|------|---------------|-----------------------|
| SCH-021 `booking_balance.balance = total - paid + refunded` | `WAVE0_01_SCHEMA.sql` | RX-003 §2 D1/D3 | "balance split into customer_owes + net_revenue — see RX-003" |
| INV-004 `Booking.getBalance()` formula | `WAVE0_00_OVERVIEW.md` (INV-004) | RX-003 §2 D1/D3 | "getBalance() replaced by getCustomerOwes()/getNetRevenue() — see RX-003" |
| `FolioResponse.balance` (API-005/006/007) | `WAVE0_02_OPENAPI.yaml` | RX-003 §2 D4 | "balance field split — see RX-003" |

---

## 4. Blast radius (verified against repo at scoping time)

`getBalance()` / `booking_balance` / `balance` consumers, exhaustive:

- `Booking.java` — getter definition (INV-004).
- `V1__wave0_schema.sql` — `booking_balance` view (SCH-021).
- `BookingBalance.java` + `BookingBalanceRepository.java` — read-only projection of the view
  (SCH-021 entity). Field set changes.
- `FolioResponse.java` — record field + javadoc (API-005/006/007).
- `DtoMapper.java` — `toFolioResponse` wiring (one call site).
- `ops-web` — wherever `folio.balance` is read (the only UI consumer).

No payment/ledger posting logic touches `balance` — ledger posts on capture (§6 charter),
independent of this read-model number. The split is **read-model only**; no posting, capture,
or refund behaviour changes.

---

## 5. Definition of done

- New Flyway migration drops + recreates `booking_balance` with `customer_owes`, `net_revenue`.
- `getCustomerOwes()` / `getNetRevenue()` replace `getBalance()`; no alias remains.
- `FolioResponse` exposes both fields; `ops-web` reads them; `balance` removed end-to-end.
- Refund worked example (§1 row 3) added as a test: book 600 → pay 600 → refund 100 →
  assert `customerOwes == 0`, `netRevenue == 500`.
- §1b updated with the three superseded-statement pointers; banners added.
- `CHANGELOG.md` entry under Stage 2.
