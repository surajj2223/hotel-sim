# WAVE0_AUDIT — Stage 2 Pre-Contract Audit of Existing Payment / Ledger Code

> **Why this document exists.** Before drafting `WAVE0_03` (the webhook / PSP event
> contract) and the Stage 2 implementation, an audit of `main` revealed that a substantial
> amount of payment, refund, and ledger **domain code already exists** — written ahead of
> its frozen contract. This artifact records what was found, what is sound, what is broken,
> and the design decisions that `WAVE0_03` must ratify. It is the "why we changed the
> existing code" record, keeping the contract-first chain honest.
>
> **Status:** audit complete; one open decision (per-line allocation rule, §5) carried into
> `WAVE0_03`.

---

## 1. What was found on `main`

Stage 1 is genuinely complete and HTTP-wired (three controllers, DTOs, exception advice,
`BookingFlowApiTest` covering happy path + 409). Java is on 21. There is also a
`docs/system-design` PRD site (HS-00..HS-08) not previously tracked here.

Beyond Stage 1, the following **Stage 2 domain layer already exists** and was not built
against a reviewed contract:

| Area | Files | Lines | State |
|------|-------|-------|-------|
| Payment service | `payment/PaymentService.java` | 235 | Coherent; createPayment / recordAuthorisation / capture / cancelAuthorisation / createRefund / settleRefund all implemented |
| Payment entities | `payment/Payment.java`, `payment/Refund.java` | 108 / 78 | Schema-backed, reference taxonomy correct |
| Webhook inbox | `payment/webhook/WebhookInbox.java` + repo | 69 | Entity only — **no handler consumes it** |
| Ledger | `ledger/LedgerService.java` | 114 | Posts REVENUE on capture, REFUND_REVERSAL on refund |
| Outbox | `ledger/OutboxProcessor.java` | 71 | `@Scheduled` poller, dispatches to ledger |
| Human-auth gate | `common/auth/HumanAuthorizationGate.java` | 37 | Built; **never invoked by any controller** |
| Enums | `CaptureMode`, `PaymentStatus` (9), `RefundStatus` (3), `PspEventCode` (7), `PostingType`, `OutboxStatus` | — | Complete and consistent |
| Invariant tests | `invariant/InvariantTest.java` | — | Mockito suite asserting INV-001..007 at service level |

**Overall judgement:** this is a *coherent, schema-backed sketch*, not throwaway code. The
DDL, entities, enums, and services all agree with each other and map to `SCH-` / `INV-`
IDs. It is good raw material — but it is unratified, unwired to HTTP, and has the specific
defects below.

---

## 2. What is sound (keep)

- **Reference taxonomy** (`Payment`, `Refund`) matches the charter exactly: opaque
  `shopperReference`, `merchantReference` as the UNIQUE reconciliation anchor, nullable
  `pspReference` / `paymentLinkId` minted by the PSP, `originalReference` chaining a refund
  to its parent payment's `pspReference`.
- **INV-006 ledger discipline** is correctly encoded: authorisation produces no posting,
  capture posts REVENUE, cancel-of-uncaptured produces no posting, refund posts a reversal.
  The `InvariantTest` proves each of these.
- **INV-005 single-capture** rule is enforced in `PaymentService.capture` and tested.
- **Amount invariants** (`captured <= authorised`, `refunded <= captured`) are enforced at
  *both* the DB (CHECK constraints) and service layers — correct defence in depth.
- **The schema already anticipates the right ledger model** (see §4): `ledger_posting` has
  a nullable `booking_line_id` and a `vertical NOT NULL` column.

---

## 3. Defects that must be fixed (in priority order)

### GAP-1 — Revenue is mis-attributed across verticals (CRITICAL; corrupts the headline feature)
`LedgerService.deriveVerticalFromBooking` stamps an entire capture's revenue to the
**first active booking line's vertical**. A cross-vertical folio (room + spa + dinner — the
POC's central proof) captured in one payment posts 100% of revenue to one vertical. This
silently corrupts "revenue reportable by vertical," the finance showcase's headline. The
code comment already concedes this is a placeholder.
**Resolution:** per-line ledger postings (decided; see §4–§5).

### GAP-2 — Outbox idempotency is claimed but not enforced (CRITICAL for a finance demo)
`OutboxProcessor` Javadoc claims at-most-one posting per event, but:
- `processPending()` loads all `PENDING` and dispatches with no per-event claim/lock, so
  overlapping scheduler ticks or retries can double-dispatch.
- `@Transactional` sits on a `protected` method called from within the same bean — Spring's
  proxy does not apply it, so the intended transaction boundary does not exist.
**Consequence:** double REVENUE postings — the worst possible bug for a ledger showcase.
**Resolution (Stage 2):** claim events with a status transition under a single committed
transaction (e.g. `PENDING → PROCESSING` via a conditional update, or `@Transactional` on a
public method on a separate bean), and make ledger writes idempotent on
`(event_id)` or `(payment_id, posting_type)`.

### GAP-3 — No inbound webhook handler exists (this is the bulk of Stage 2)
`WebhookInbox` + `idempotency_key UNIQUE` exist, and `PaymentService.recordAuthorisation` /
`settleRefund` are written to be *called by* a handler — but nothing receives PSP webhooks.
This is the largest single piece of Stage 2 work and has zero code today.

### GAP-4 — No HTTP boundary for payments; auth gate unwired
No `createPaymentLink` / `capturePayment` / `cancelAuthorisation` / `refundPayment`
endpoints exist. `HumanAuthorizationGate` (INV-007) is unit-tested in isolation but never
invoked by a controller, so the server-side commit gate is not actually enforced on any
real request path.

### GAP-5 — Contract written after the code (sequencing inversion)
`WAVE0_03` was never authored; `WAVE0_02_OPENAPI.yaml` has no payment paths. The payment
domain code therefore exists ahead of its frozen contract — the exact inversion the
contract-first discipline is meant to prevent. This audit + `WAVE0_03` + an OpenAPI
extension close the gap retroactively.

---

## 4. Decided: per-line ledger postings

A capture posts **one REVENUE row per booking line it covers**, each carrying that line's
own `vertical` and its allocated amount, all sharing the capture's `payment_id`,
`pspReference`, and `merchantReference`. A refund posts **one REFUND_REVERSAL row per line**
it reverses, mirroring the structure with a negative amount.

**This requires no schema migration.** It uses columns already present in the frozen Wave 0
schema:
- `ledger_posting.booking_line_id` — currently always null; becomes populated.
- `ledger_posting.vertical NOT NULL` — already forces every posting to name a vertical
  (this constraint is *why* the GAP-1 hack is a defect rather than an acceptable deferral).
- `booking_line.vertical` and `booking_line.line_amount` — already carried per line.

`LedgerService.deriveVerticalFromBooking` is deleted; `postCapture` / `postRefund` iterate
the covered lines instead.

---

## 5. OPEN DECISION for WAVE0_03 — partial-capture allocation rule

Per-line postings are unambiguous for a **full** capture (each line posts its full
`line_amount`). The open question is how to split a **partial** capture across lines, since
a partial capture is one money movement smaller than the folio total. Options:

- **(a) Proportional** — distribute the captured amount across lines pro-rata by
  `line_amount`, with a deterministic rounding rule for the remainder (minor units must sum
  exactly). Simple; but a "partial capture" rarely means "a bit of every line."
- **(b) Fill-by-line-order** — satisfy lines fully in a defined order until the captured
  amount is exhausted, leaving a final line partially or wholly uncaptured. Models "we
  captured the room but not yet the spa" cleanly.
- **(c) Capture-targets-explicit-lines** — the capture request names which line(s) it
  covers; no implicit allocation. Most precise; pushes the choice to the caller and needs a
  request-shape decision.

**Recommendation to confirm:** for the POC, **(b) fill-by-line-order** by booking-line
`created_at` — it matches the realistic "capture at checkout, room first" narrative, keeps
the math exact in minor units, and needs no change to the capture request shape. Revisit if
a demo utterance requires line-targeted capture.

This decision is the one substantive thing `WAVE0_03` must settle before any Stage 2 code.

---

## 6. Recommended sequence (unchanged contract-first chain)

1. **This audit** — committed first (record of why existing code will change).
2. **`WAVE0_03`** — webhook / PSP event contract: inbound webhook shape, idempotency-key
   derivation, `merchantReference → Payment` matching, the event→state-transition table
   over the existing `PspEventCode` / `PaymentStatus` / `RefundStatus` enums, and the §5
   allocation rule.
3. **`WAVE0_02` extension** — payment write paths + webhook receiver in OpenAPI, all gated
   by `X-Human-Auth` (INV-007).
4. **Stage 2 slice** (Claude Code prompt) — webhook controller + payment controller +
   `payments-sim` + GAP-1 and GAP-2 fixes + wire the auth gate + behavioural tests, built
   so `ops-web` could drive it (the charter's acceptance test).

---

## 7. Traps to carry into the Stage 2 prompt

- Do **not** add a third overlap-availability query (two already exist:
  `BookingLineRepository.lockedCountCommitted`, `ProductRepository.countCommittedQuantity`).
- `VerticalStrategyRegistry` silently fails to register a strategy missing the
  `VerticalStrategyRegistration` marker interface — flag explicitly.
- Do **not** modify the frozen Wave 0 schema; per-line postings are deliberately designed to
  fit it. Flag, don't fix, any apparent conflict with a frozen contract.
- `@Transactional` on non-public / self-invoked methods is a no-op (root cause of GAP-2) —
  do not reproduce the pattern.
