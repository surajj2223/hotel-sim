# WAVE0_03 — Webhook / PSP Event Contract

> **What this freezes.** The event vocabulary `payments-sim` emits, the exact payload shape
> of each event, the idempotency model, and the rules `core-api` follows when consuming
> them (matching, stamping, state transitions, ledger effects). Once frozen, neither
> `payments-sim` nor `core-api` payment orchestration may diverge from this without the
> change protocol in `WAVE0_00 §4`.
>
> **Status:** `FROZEN` (authoritative status in `WAVE0_00 §1b` Freeze Ledger).
> **Owner / arbiter:** Desk.
> **Depends on:** `WAVE0_01_SCHEMA.sql` (enums `ENM-004..009`; tables `SCH-030..071`),
> `WAVE0_02_OPENAPI.yaml` Stage 2 slice (`API-008..014`; freezes as a matched pair with this file).
> **Informed by:** `WAVE0_AUDIT.md` (existing payment/ledger code; GAP-1..GAP-5).

---

## 0. Reference vocabulary (defined elsewhere — cited, not redefined)

Enums are owned by `WAVE0_01_SCHEMA.sql`; this contract references them.

- `PspEventCode` (ENM-007): `AUTHORISATION`, `CAPTURE`, `CAPTURE_FAILED`, `CANCELLATION`,
  `REFUND`, `REFUND_FAILED`, `AUTH_EXPIRY`.
- `PaymentStatus` (ENM-005): `PENDING`, `AUTHORISED`, `CAPTURED`, `CAPTURE_FAILED`,
  `CANCELLED`, `AUTH_EXPIRED`, `PARTIALLY_REFUNDED`, `REFUNDED`, `FAILED`.
- `RefundStatus` (ENM-006): `PENDING`, `REFUNDED`, `REFUND_FAILED`.
- `CaptureMode` (ENM-004): `IMMEDIATE`, `MANUAL`.
- `PostingType` (ENM-008): `REVENUE`, `REFUND_REVERSAL`.

Reference taxonomy (ENM-010): `shopperReference` (ours, opaque, stable), `merchantReference`
(ours, per attempt, the reconciliation anchor, UNIQUE), `pspReference` (PSP-minted),
`paymentLinkId` (PSP-minted), `originalReference` (a refund's link to its parent payment's
`pspReference`).

---

## 1. Requirements table

| ID | Requirement | Acceptance criteria | Depends-on |
|----|-------------|---------------------|------------|
| WHK-001 | `payments-sim` mints `pspReference` and `paymentLinkId` in realistic formats; it stores but never invents `shopperReference` / `merchantReference`. | A link-creation call echoes our two references and returns a `paymentLinkId`; auth returns a `pspReference`. Formats per §2. | ENM-010 |
| WHK-002 | Every webhook carries a complete envelope: `eventCode`, `merchantReference`, `pspReference`, `amount`+`currency`, `eventId`, `idempotencyKey`, `occurredAt`, and event-specific fields. | Schema in §3 validates against every emitted event. | ENM-007 |
| WHK-003 | `idempotencyKey` is derived deterministically from the PSP event identity (`pspReference` + `eventCode` + sequence), stable across redeliveries. | Two redeliveries of the same event carry identical `idempotencyKey`. | SCH-071 |
| WHK-004 | `core-api` matches every inbound event to a `Payment` by `merchantReference`; refund events match a `Refund` by its own `merchantReference`. | Unknown reference → `404`-class log + `webhook_inbox` row with no payment mutation. | SCH-031, SCH-040 |
| WHK-005 | `core-api` deduplicates on `webhook_inbox.idempotency_key` (UNIQUE). A duplicate is acknowledged `200` and produces no second state change or posting. | Replaying a processed event mutates nothing; `webhook_inbox` has exactly one row. | SCH-070, SCH-071 |
| WHK-006 | Inbound `AUTHORISATION` stamps `pspReference` + `amountAuthorised` + `authExpiresAt`, sets `PaymentStatus.AUTHORISED`. **No ledger posting** (INV-006). | `recordAuthorisation` path; `verify(outbox, never())`. | SCH-030, ENM-005, INV-006 |
| WHK-007 | Inbound `CAPTURE` sets `CAPTURED`, enqueues one `PAYMENT_CAPTURED` outbox event → per-line `REVENUE` postings (§5). Single-capture only (INV-005). | One capture → N postings (one per covered line), each with the line's `vertical`; second capture rejected `409`. | SCH-032, INV-005, INV-006 |
| WHK-008 | Inbound `CANCELLATION` of an uncaptured auth sets `CANCELLED`; **no posting, no reversal** (INV-006). | `cancelAuthorisation` path; `verify(outbox, never())`. | ENM-005, INV-006 |
| WHK-009 | Inbound `REFUND` settles the matching `Refund` (`RefundStatus.REFUNDED`), updates payment to `PARTIALLY_REFUNDED`/`REFUNDED`, enqueues one `REFUND_SETTLED` outbox event → per-line `REFUND_REVERSAL` postings (negative). | Refund chain via `originalReference`; postings sum to `-refundAmount`; cannot exceed captured (SCH-033). | SCH-033, SCH-040, SCH-051, INV-006 |
| WHK-010 | `CAPTURE_FAILED` / `REFUND_FAILED` set the corresponding failed status; no posting. | Status reflects failure; ledger untouched. | ENM-005, ENM-006 |
| WHK-011 | `AUTH_EXPIRY` sets `AUTH_EXPIRED` on an uncaptured auth; no posting. Captured payments are unaffected. | Expiry on `AUTHORISED` → `AUTH_EXPIRED`; expiry on `CAPTURED` → ignored. | ENM-005 |
| WHK-012 | Partial-capture allocation across booking lines is **fill-by-line-order** by `booking_line.created_at` ascending (§5). Minor-unit math is exact; allocations sum to the captured amount. | Worked examples in §5 reproduce exactly. | SCH-022, GAP-1 |
| WHK-013 | Outbox consumption is idempotent and correctly transactional: each event produces its postings at most once, even under concurrent ticks or retries (fixes GAP-2). | Concurrent processing test produces no duplicate postings. | SCH-060, GAP-2 |
| WHK-014 | The webhook receiver endpoint is **not** human-gated (it is PSP→server, not operator→server), but is authenticated by a shared PSP signature/secret header. Operator-initiated writes that *trigger* PSP calls (capture/refund) are human-gated per `API-`/INV-007. | Webhook without valid signature → `401`; capture/refund without `X-Human-Auth` → `428`. | API-008..012, INV-007 |
| WHK-015 | Completion is asynchronous (capture/cancel/refund return `202`; state + postings land on the webhook). `payments-sim` provides a test-only synchronous webhook-drive seam so end-to-end tests are deterministic. | Smoke test asserts final state + postings with no sleep/poll; production path stays async. See §6a. | API-010/011/012, §6a |
| WHK-016 | **Scoped allocation, additive over WHK-012.** When a payment carries `payment_line` coverage rows, a `CAPTURE`/`REFUND` allocates the event amount across **exactly those lines**, scaled to their recorded coverage amounts (single rounding remainder → first covered line). Absent coverage rows, WHK-012 fill-by-line-order applies **unchanged**. A refund reverses against the **parent payment's** coverage when present (never re-derived from the booking). Coverage amounts must sum to the payment amount (else `400`). See §5.1. | Scoped capture posts one `REVENUE` row per covered line carrying that line's `vertical`; an unscoped capture reproduces the §5 worked examples byte-identically; coverage-sum mismatch → `400`. Worked proofs in §5.1. | SCH-022, WHK-012, **SCH-030** (new `payment_line`), GAP-1 |

---

## 2. PSP reference formats (WHK-001)

`payments-sim` mints, on the stated event:

| Reference | Minted at | Format (POC) | Example |
|-----------|-----------|--------------|---------|
| `paymentLinkId` | link creation | `PL-` + 16 base62 | `PL-7sG2k9Qable0PmZ4` |
| `pspReference` (payment) | `AUTHORISATION` | `PSP-` + 16 base62 | `PSP-aZ91kQ2mn0PdL3xR` |
| `pspReference` (refund) | `REFUND` | `PSP-` + 16 base62 (distinct) | `PSP-mn0PdL3xRaZ91kQ2` |

`shopperReference` (`SHPR-…`) and `merchantReference` are supplied by `core-api`; the PSP
stores and echoes them, never mints them.

---

## 3. Webhook envelope (WHK-002)

> ⚠️ **The "`pay-web`/`payments-sim` produce" phrasing below is superseded in part by [RX-001](refactor-x/RX-001-psp-direction-and-statefulness.md)** (see Freeze Ledger `WAVE0_00 §1b`). Original text preserved; do not build against the superseded portion without checking the ledger. The envelope itself (WHK-002) is unchanged.

Common envelope on every event (POC JSON; `pay-web`/`payments-sim` produce, `core-api`
consumes):

```jsonc
{
  "eventId":        "evt_<uuid>",          // PSP's id for THIS delivery attempt's event
  "eventCode":      "AUTHORISATION",        // PspEventCode (ENM-007)
  "idempotencyKey": "<pspReference>:<eventCode>:<seq>",  // WHK-003; stable across redeliveries
  "merchantReference": "MR-<...>",          // our anchor (WHK-004)
  "pspReference":   "PSP-<...>",            // present from AUTHORISATION onward
  "amount":         54000,                  // minor units (BIGINT); meaning per event below
  "currency":       "GBP",
  "occurredAt":     "2026-06-12T14:03:21Z",
  "success":        true                    // false for *_FAILED variants
}
```

Event-specific fields:

| eventCode | adds | `amount` means |
|-----------|------|----------------|
| `AUTHORISATION` | `authExpiresAt` | amount authorised |
| `CAPTURE` | — | amount captured (≤ authorised) |
| `CAPTURE_FAILED` | `reason` | attempted capture amount |
| `CANCELLATION` | — | 0 (or authorised, informational) |
| `REFUND` | `originalReference`, `refundMerchantReference` | amount refunded |
| `REFUND_FAILED` | `originalReference`, `reason` | attempted refund amount |
| `AUTH_EXPIRY` | — | 0 |

> Note: refund events match a **`Refund`** row by `refundMerchantReference`, not the
> payment's `merchantReference` (WHK-004). The payment is located via the refund's parent.

---

## 4. Consumer rules — event → state transition table (WHK-004..011)

`core-api` processing order for each inbound event:
1. Persist a `webhook_inbox` row; **if `idempotency_key` already exists, stop and ack `200`** (WHK-005).
2. Resolve the target (`Payment` by `merchantReference`; `Refund` by `refundMerchantReference`). Unknown → log + ack, no mutation (WHK-004).
3. Apply the transition below atomically; enqueue outbox event where stated.
4. Stamp `webhook_inbox.processed_at`.

| eventCode | precondition | payment/refund effect | outbox | ledger (via outbox) |
|-----------|--------------|-----------------------|--------|---------------------|
| `AUTHORISATION` | status ∈ {PENDING} | stamp `pspReference`, `amountAuthorised`, `authExpiresAt`; → `AUTHORISED` | none | none |
| `CAPTURE` | status ∈ {AUTHORISED}, or {PENDING}+IMMEDIATE | set `amountCaptured`; → `CAPTURED`; single-capture (INV-005) | `PAYMENT_CAPTURED` | per-line `REVENUE` (§5) |
| `CAPTURE_FAILED` | status ∈ {AUTHORISED, PENDING} | → `CAPTURE_FAILED` | none | none |
| `CANCELLATION` | `amountCaptured == 0` | → `CANCELLED` | none | none |
| `REFUND` | payment captured; refund exists PENDING; `amount ≤ captured − refunded` | settle `Refund` → `REFUNDED`; bump `amountRefunded`; → `PARTIALLY_REFUNDED`/`REFUNDED` | `REFUND_SETTLED` | per-line `REFUND_REVERSAL` (§5) |
| `REFUND_FAILED` | refund PENDING | refund → `REFUND_FAILED` | none | none |
| `AUTH_EXPIRY` | status ∈ {AUTHORISED} | → `AUTH_EXPIRED` | none | none |

Any event arriving against a precondition-violating state fails loudly (logged; payment
left unchanged; surfaced for reconciliation) rather than silently coercing state.

---

## 5. Per-line ledger allocation (WHK-007, WHK-009, WHK-012)

**Decision (from `WAVE0_AUDIT §4–5`):** a capture/refund posts **one ledger row per covered
booking line**, each carrying that line's `vertical` and `booking_line_id`, sharing the
event's `payment_id` / `pspReference` / `merchantReference`. This is what makes revenue
reportable by vertical. **No schema change** — uses the existing nullable
`ledger_posting.booking_line_id` and the `vertical NOT NULL` column.

**Allocation rule — fill-by-line-order (WHK-012):** order the booking's active lines by
`created_at` ascending. Walk them, assigning each line `min(remaining, line_amount)` of the
captured amount, until the captured amount is exhausted. Each non-zero assignment becomes
one `REVENUE` posting. Refund reversals walk the same order, producing negative postings.

Worked examples — folio = line R (room, £500 = 50000) created first, line S (spa, £200 =
20000) created second; `currency = GBP`:

- **Full capture 70000:** R→50000 (REVENUE/ROOM), S→20000 (REVENUE/SPA). Sum 70000. ✓
- **Partial capture 54000:** R→50000 (REVENUE/ROOM), S→4000 (REVENUE/SPA). Sum 54000. ✓
  (Room filled first; spa partially.)
- **Partial capture 30000:** R→30000 (REVENUE/ROOM). S→0 (no posting). Sum 30000. ✓
- **Refund 6000 after the 54000 capture:** walk R then S — R has 50000 captured-revenue, so
  R→−6000 (REFUND_REVERSAL/ROOM). Net room revenue 44000; spa untouched.

Exactness: all arithmetic is in minor units (BIGINT); fill-by-order never creates rounding
remainders (unlike pro-rata), so allocations always sum to the event amount.

> **Refund-reversal ordering note (flag for sign-off) — RESOLVED by WHK-016 (§5.1).** The
> example above reverses against line order (room first); that remains the behaviour for a
> **folio-wide (unscoped)** payment. Line-targeted refunds are now first-class via scoped
> coverage: a refund follows the **parent payment's** `payment_line` rows when present, so a
> spa-scoped payment's refund reverses the *spa* line specifically. See §5.1.

---

## 5.1 Scoped allocation (WHK-016) — additive over WHK-012

**Status: FROZEN (Stage 4 Slice 1).** Signed off; recorded in the Freeze Ledger
(`WAVE0_00 §1b`), which remains authoritative. Frozen-at: this reconciliation commit;
implemented in bb9395c / af8c42e. Change only via a new WHK- amendment, never in place.

WHK-012's fill-by-line-order answers "the guest paid for the lot" — one folio-wide payment,
allocated top-down. It cannot represent a payment that settles *specific* lines: a £200 spa
payment on a folio whose room line sorts first would credit the **room** vertical (the GAP-1
class of error, for sequential cross-vertical and multi-method tenders). WHK-016 adds a
**many-to-many** `payment → booking_line` association carrying a per-line amount
(`payment_line`; lives in `core-api`'s schema, additive — no change to `payment` /
`booking_line` / `ledger_posting`). It is deliberately not a payment→line FK, so it supports
**one card → many lines** and **one line → many cards** (split tender) at once.

**Allocation rule (scoped):** when a payment has `payment_line` coverage rows, order them by
the covered line's `created_at` ascending; give each line
`floor(eventAmount × coverage_i ÷ Σcoverage)` of the captured (or refunded) amount, then
assign the single rounding remainder to the **first** covered line. Each non-zero share
becomes one `REVENUE` (capture) or `REFUND_REVERSAL` (refund, negative) posting carrying that
line's `vertical`. For a **full** capture (`eventAmount == Σcoverage`) each line receives
exactly its coverage and there is no remainder. Minor-unit math throughout; allocations sum
exactly to the event amount (the same Σ-guard as WHK-012).

**Fallback:** a payment with **no** coverage rows is folio-wide and uses WHK-012 unchanged —
this is the untouched default proven by `LedgerCorrectnessTest`.

**Refunds (resolves the ordering-note flag):** a refund reverses revenue against the lines its
**parent payment** posted to. When the parent has coverage, scale that coverage to the refund
amount (same rule, negative); otherwise WHK-012 fill-by-line-order. The scope is read from the
parent payment, never re-derived from the booking's current active lines.

**Coverage validity:** coverage amounts must sum to the payment's requested amount and every
covered line must belong to the booking; a violation is rejected `400` (never partially
accepted).

Worked examples — folio = line R (room, £500 = 50000) created first, line S (spa, £200 =
20000) created second; `currency = GBP`:

- **Spa-scoped payment, capture 20000** (coverage S→20000): S→20000 (REVENUE/**SPA**). R
  receives **nothing**. Contrast WHK-012, where 20000 would fill R first.
- **Room-scoped payment, capture 50000** (coverage R→50000): R→50000 (REVENUE/ROOM); S
  untouched. (Multi-method: a second, spa-scoped payment posts S independently.)
- **Two-line scoped payment 70000, partial capture 35000** (coverage R→50000, S→20000):
  R→25000, S→10000 (proportional; sum 35000). A remainder, if any, lands on R.
- **Refund 6000 against a spa-scoped (S→20000) payment:** S→−6000 (REFUND_REVERSAL/SPA);
  room untouched — the spa line specifically, per the parent payment's scope.

---

## 6. Idempotency & outbox correctness (WHK-005, WHK-013) — fixes GAP-2

Two distinct idempotency layers, both required:

1. **Inbound dedupe (WHK-005):** `webhook_inbox.idempotency_key` UNIQUE. Insert-first; a
   duplicate key insert fails → treat as already-processed, ack `200`, mutate nothing.
2. **Outbox→ledger idempotency (WHK-013):** the processor must
   (a) **claim** each event with a conditional status transition `PENDING → PROCESSING`
   (single-row update guarded on current status) so a second tick cannot pick up the same
   event; (b) run the ledger write and the `PROCESSING → PROCESSED` transition in **one
   public, proxied `@Transactional` method on a separate bean** (the audit found the
   current `protected` self-invoked `@Transactional` is a no-op); and (c) make the ledger
   write itself idempotent — guard on `(event_id)` or `(payment_id, posting_type)` so a
   replay cannot double-post even if a crash lands between write and status flip.

Acceptance: a test that dispatches the same outbox event twice (and one that runs two ticks
concurrently) produces exactly the §5 posting set once.

---

## 6a. Asynchronous completion & the test seam (WHK-015) — DECIDED

**Decision.** Capture, cancellation, and refund are **asynchronous**. The operator-facing
endpoints (`API-010/011/012`) return `202 Accepted` having only *requested* the action at
the PSP; the authoritative state change and all ledger postings happen when the
corresponding webhook arrives (§4). This is deliberate: it is what makes `payments-sim` a
*real* HTTP+webhook integration rather than an in-process call, which is the POC's thesis.
Authorisation is inherently async already (the customer authorises on `pay-web`; `core-api`
learns of it only via the `AUTHORISATION` webhook), so a synchronous capture path would
produce an inconsistent half-async system. Rejected alternative: synchronous capture
(`200` with final state, webhook as reconciliation echo).

**Consequence for tests (the seam).** Because state lands on the webhook, tests must not
assert final state on the `202` response. To keep tests deterministic (no sleeps/polling),
`payments-sim` exposes a **synchronous webhook-drive seam** for test use only:

| ID | Requirement | Acceptance |
|----|-------------|------------|
| WHK-015 | `payments-sim` can deliver a given event's webhook **synchronously** when invoked through a test-only trigger (e.g. a `?sync=true` flag or a dedicated test endpoint), so an end-to-end test runs `capture → drive webhook → assert CAPTURED → assert per-line postings` with no timing dependence. In normal operation, webhooks are delivered asynchronously after the PSP action. | Smoke test asserts `CAPTURED` + posting set deterministically; production path remains async. |

The seam is a property of `payments-sim` (the fake PSP), not of `core-api`; `core-api`'s
webhook receiver (`API-013`) is identical whether the event arrives sync-in-test or
async-in-prod. This keeps the integration shape honest while removing flakiness.

---

## 7. Security boundary (WHK-014)

- **Webhook receiver** (`payments-sim → core-api`): authenticated by a shared PSP signature
  header (`X-PSP-Signature`, HMAC over the raw body with a shared secret in POC config).
  **Not** human-gated — it is machine-to-machine. Invalid/absent signature → `401`.
- **Operator-initiated payment writes** (`capturePayment`, `refundPayment`,
  `cancelAuthorisation`, `createPaymentLink`) are human-gated via `X-Human-Auth` (INV-007),
  specified in `WAVE0_02` (extension pending). These are the calls that *cause* `core-api`
  to call the PSP; the resulting webhook is the PSP's asynchronous confirmation.

This keeps the two trust boundaries distinct: operator authority gates the *request* to the
PSP; PSP signature authenticates the *callback*.

---

## 8. Accountability block

| Field | Value |
|-------|-------|
| Owner | Desk (arbiter) |
| Status | `FROZEN` (authoritative in `WAVE0_00 §1b`). Next: `IN-BUILD` when Stage 2 code starts. |
| Sign-off | ☑ frozen this commit — implementation may proceed against WHK-001..015. |
| Consumers | `payments-sim`, `pay-web`, `core-api` payment orchestration, integration owner |

> ⚠️ **The `pay-web` mention in the Consumers row above is superseded in part by [RX-001](refactor-x/RX-001-psp-direction-and-statefulness.md)** (see Freeze Ledger `WAVE0_00 §1b`). Original text preserved; do not build against the superseded portion without checking the ledger.

## 9. Verification log

*(Empty until Wave 1 / Stage 2 build. Each `WHK-` ID records: what was built, commit/PR ref, the test that proves it.)*

| ID | Built | Commit/PR | Proving test |
|----|-------|-----------|--------------|
| WHK-001..015 | — | — | — |
| WHK-016 | Scoped payment→line allocation + scoped refund; `payment_line` (V3, additive) | bb9395c, af8c42e (PR #22) | `ScopedAllocationApiTest`, `ScopedRevenueHttpApiTest`, `LedgerCorrectnessTest` |

## 10. Changelog

| Version | Date | Change |
|---------|------|--------|
| 0.1 | (draft) | Initial draft. Event vocabulary, envelope, transition table, per-line allocation (fill-by-line-order), two-layer idempotency, security boundary. Authored against existing payment/ledger code per `WAVE0_AUDIT`. |
| 0.2 | (draft) | Added §6a + WHK-015: async completion (`202`, webhook-completed) recorded as a decided choice, with a test-only synchronous webhook-drive seam in `payments-sim` for deterministic end-to-end tests. Updated dependency line — `WAVE0_02` Stage 2 slice now drafted, freezes as a matched pair. |
| 0.3 | 2026-06-14 | Added **WHK-016 (DRAFT)**: scoped payment→line allocation, additive over WHK-012, with new §5.1 and a requirements-table row. Resolved the §5 "Refund-reversal ordering note" flag by pointing it at WHK-016 (line-targeted refunds via the parent payment's coverage). No frozen WHK requirement text changed; WHK-012 fallback untouched. Pending Desk sign-off to freeze. |
| 0.4 | 2026-06-17 | WHK-016 frozen post-implementation (§1c reconciliation); status line §5.1 DRAFT→FROZEN; §9 verification row filled (bb9395c/af8c42e). Freeze authoritative in `WAVE0_00 §1b`. |
