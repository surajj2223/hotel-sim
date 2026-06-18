# Claude Code prompt — Slice 1: RX-003 balance split (`customerOwes` + `netRevenue`)

**Plan-then-wait.** Produce a plan, list the files you will touch, and STOP at the gate.
Do not write code until Desk says go.

## Precondition (verify first, do not assume)
- `RX-003` must be **FROZEN** in the Freeze Ledger (`WAVE0_00_OVERVIEW.md §1b`) with
  `customer_owes` / `net_revenue` as the forward spec, and the three pointer banners present
  (SCH-021 view, INV-004 getter, `FolioResponse.balance`). If RX-003 is not yet FROZEN in §1b,
  STOP and flag — do not implement against a DRAFT contract (CLAUDE.md, the one rule).

## Scope (exactly this, nothing else)
Replace the single overloaded `balance` with two separately-derived fields, per RX-003 §2:
- `customerOwes = max(0, total_amount - amount_paid)` — settlement predicate.
- `netRevenue   = amount_paid - amount_refunded` — finance read.
Read-model only. **No** capture, refund, posting, or ledger behaviour changes.

## Files (target ≤6; if it grows past 6, stop and flag a split)
1. New Flyway migration `V6__balance_split.sql` (next ordinal — verify the highest existing V
   number first; do not reuse). `DROP VIEW booking_balance; CREATE VIEW booking_balance` with
   columns `booking_id, total_amount, amount_paid, amount_refunded, customer_owes, net_revenue`.
   `customer_owes = GREATEST(0, total_amount - amount_paid)`, `net_revenue = amount_paid -
   amount_refunded`. **Do not edit `V1__wave0_schema.sql`** (frozen; pointer banner only, added
   by Desk at freeze — not your job).
2. `BookingBalance.java` — projection field set: replace `balance` with `customerOwes`,
   `netRevenue` (map to the new view columns).
3. `Booking.java` — remove `getBalance()`; add `getCustomerOwes()` (`Math.max(0, totalAmount -
   amountPaid)`) and `getNetRevenue()` (`amountPaid - amountRefunded`). **Remove, do not alias**
   the old getter — a stale reader must fail to compile.
4. `FolioResponse.java` — replace the `balance` record component with `customerOwes`,
   `netRevenue`; update the javadoc (drop the SCH-021 `total - paid + refunded` formula line).
5. `DtoMapper.java` — `toFolioResponse`: wire the two new getters instead of `getBalance()`.
6. `ops-web` — the single `folio.balance` read site → render `customerOwes` (+ `netRevenue` if
   the screen shows revenue). Grep for `balance` first to find every reference.

## Traps (call these out in the plan; they are how this slice goes wrong)
- **T-A — wrong Flyway ordinal.** Reusing or guessing a V-number silently no-ops or fails on a
  clean DB. Verify the highest existing migration number; use the next one.
- **T-B — aliasing the old getter.** `getBalance()` delegating to `getCustomerOwes()` defeats
  the point: a stale caller binds to the wrong number silently. Remove it outright.
- **T-C — editing the frozen V1 / the SCH-021 verification log.** The view changes via a NEW
  migration. The frozen schema file and its verification log are untouched (RX-002 precedent).
- **T-D — touching capture/refund/posting.** This is read-model only. If you find yourself in
  `PaymentOrchestrator` or ledger code, stop — you have left scope.
- **T-E — `max(0,…)` dropped.** Without `GREATEST`/`Math.max`, an over-capture renders a
  negative `customerOwes` and a completed folio looks un-completable. The clamp is load-bearing.
- **T-F — missed `ops-web` reference.** A leftover `folio.balance` read compiles in JS and
  renders `undefined`. Grep the whole UI tree.

## Definition of done
- `customerOwes` / `netRevenue` replace `balance` end-to-end; no `getBalance` / `.balance`
  reference survives anywhere (grep both trees to prove it).
- New test: book 600 → pay 600 → refund 100 → assert `customerOwes == 0`, `netRevenue == 500`
  (RX-003 §1 row 3 / §5). Plus the unpaid case → `customerOwes == 600`.
- Commit message cites `[RX-003]`.
- `CHANGELOG.md` entry (version-number first column, per CLAUDE.md).

## Out of scope (do not pull forward)
- `completeLine` / `completeFolio` — that is Slice 2, and depends on this `customerOwes`.
- Any negative-`customerOwes` liability tracking (hotel-owes-customer) — out of POC.
