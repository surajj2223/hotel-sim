# Known design decision — scoped payment→line allocation (`payment_line`, WHK-016)

> **Type:** design-decision record (not a frozen contract). The enforceable seam is
> **WHK-016** in `WAVE0_03_WEBHOOK_PSP_CONTRACT.md §5.1`; the Freeze Ledger
> (`WAVE0_00 §1b`) is authoritative for its freeze state. This file records *why* the shape
> is a many-to-many association and which real cases forced it.

## 1. The problem

A `payment` referenced `booking_id` only — it recorded **how much** money arrived but never
**which lines** it was for. Ledger allocation therefore *guessed*: it walked the booking's
active lines by `created_at` ascending (WHK-012 fill-by-line-order) and filled top-down. That
is correct for a folio-wide "pay for the lot" payment (and is proven by `LedgerCorrectnessTest`),
but it is **unrepresentable** for the cases that define Stage 4:

- **Sequential cross-vertical.** Room auth £1,800 is held; later a £200 spa payment is captured
  *for the spa only*. Fill-by-line-order credits the £200 to the **room** line (it sorts first),
  so the books misattribute the vertical that earned the revenue. This is the GAP-1 class of
  error resurfacing through the allocation guess.
- **Multi-method.** Room on Visa, spa on Amex — two payments on one folio, each settling its
  own line(s) and tracing to its own `pspReference`.
- **Split tender.** One £1,800 room line paid £1,000 on Visa + £800 on Amex — one line, two
  payments.

## 2. Why many-to-many (not a payment→line FK)

A foreign key from `payment` to `booking_line` would model "one payment settles one line" and
break **both** real shapes at once:

- one card paying **many** lines (a single Visa payment covering room + spa), and
- one line paid by **many** cards (split tender on the room line).

The honest model is a **many-to-many association carrying a per-line amount**: `payment_line`
(`payment_id`, `booking_line_id`, `amount`). A payment may cover several lines; a line may be
covered by several payments. Allocation reads the recorded amounts instead of guessing.

## 3. The decision (as implemented)

- **Additive + optional.** A payment MAY carry `payment_line` coverage rows. When present →
  allocate the captured/refunded amount across exactly those lines, scaled to their coverage
  (single rounding remainder → first covered line, deterministic). When absent → the WHK-012
  fill-by-line-order fallback is used **unchanged**. This keeps the frozen WHK-012 behaviour
  and `LedgerCorrectnessTest` green and unmodified.
- **Refunds follow the parent payment's scope** (resolves the §5 "Refund-reversal ordering
  note" flag). You can only reverse revenue you posted, against the lines you posted it to —
  so a refund reverses against the **parent payment's** coverage, never re-derived from the
  booking's current active lines.
- **Coverage must fully cover the payment.** Coverage amounts must sum to the payment's
  requested amount and every covered line must belong to the booking; a violation is rejected
  `400` (never silently accepted as partial coverage).
- **Schema is additive only.** `payment_line` is a new table (`V3__payment_line_scoping.sql`);
  `payment` / `booking_line` / `ledger_posting` are untouched. `payment_line` lives in
  `core-api`'s Postgres, never the `payments-sim` database.

## 4. Out of scope (explicitly)

Coverage-enforcement / checkout-blocking; incremental-auth; multi-capture; pro-rata across
*uncovered* lines; any change to WHK-012's math for the no-coverage path; `getRevenue` /
Stage-6 reads. The companion roll-up `booking.amount_authorised` (live folio "secured"
number) is **visible only** — no enforcement.

## 5. Open follow-up — HTTP exposure

Scoping is wired at the **service layer** (`PaymentService.createPaymentLink(..., coverage)`).
Exposing it on the operator-facing `createPaymentLink` HTTP body would change the **frozen**
API-008 DTO (`WAVE0_02_OPENAPI.yaml`) and must go through the API-contract change protocol
(`WAVE0_00 §4`) — deferred to a separate slice so this one stays within its authorised
contract surface (WHK-016 only). Until then, `ops-web` cannot send coverage; per the
"no agent-only capability" rule, the HTTP surface remains folio-wide for both heads.
