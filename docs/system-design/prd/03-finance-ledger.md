# HS-03 — Finance & Ledger

| Field            | Value                                                        |
|------------------|--------------------------------------------------------------|
| **ID**           | HS-03                                                        |
| **Title**        | Finance & Ledger                                             |
| **Version**      | 0.1                                                          |
| **Contract Status** | Draft                                                     |
| **Build Status** | Not Started                                                  |
| **Date**         | 2026-06-10                                                   |
| **Owner**        | Track C (Ledger / finance)                                   |
| **Freezes**      | Capture modes · Ledger posting rules · Refunds · Reference taxonomy |

---

## Overview

Finance is a **first-class showcase feature**, not credibility dressing. This PRD
defines the payment reference vocabulary, capture modes, ledger posting rules, refund
handling, and the outbox pattern that decouples postings from booking events.

---

## Payment Reference Taxonomy (Adyen-flavoured)

| Reference | Minted by | Meaning | Mutability |
|-----------|-----------|---------|------------|
| `shopperReference` | Us (`core-api`) | Stable customer identifier sent to PSP; on Customer entity. | Immutable — never changes. |
| `merchantReference` | Us (`core-api`) | Our reference per **payment attempt**; reconciliation anchor. | Immutable per attempt. |
| `pspReference` | `payments-sim` | PSP's own transaction id; returned on the first webhook. | Minted by PSP; we store and never alter. |
| `paymentLinkId` | `payments-sim` | PSP's id for a hosted payment link. | Minted by PSP; we store and never alter. |

- **Reconciliation** anchors on `merchantReference`.
- **Customer continuity** (across multiple stays / payment attempts) anchors on `shopperReference`.
- All four references appear on the `Payment` entity and travel through the ledger so finance
  reads can trace through to the PSP transaction.

---

## Capture Modes

| Mode | Behaviour | Default vertical |
|------|-----------|-----------------|
| `IMMEDIATE` | Auth and capture happen together in a single operation. Revenue posts at this moment. | F&B |
| `MANUAL` | Auth now (card hold), capture later (explicit operator action). Revenue posts only at capture. | Rooms |

- Default supplied by the vertical strategy (`defaultCaptureMode()`), but explicit and
  overridable on the payment request.
- **Partial capture IN:** authorise £600, capture £540, release the rest. One auth → at most
  one capture; the remainder is released automatically.
- **Multi-capture OUT:** one auth, one capture. No incremental auth.

---

## Ledger Posting Rules

| Event | Ledger effect | Notes |
|-------|---------------|-------|
| Auth (MANUAL) | No posting. Auth is a hold, not revenue. | — |
| Capture (MANUAL) | Posts revenue entry for the captured amount. | Carries `pspReference` + `merchantReference`. |
| Auth+Capture (IMMEDIATE) | Posts revenue entry. | Same as capture — happens atomically. |
| Cancel uncaptured auth | No reversal. Nothing was posted. | — |
| Refund | Posts reversal entry (reduces net revenue). | Linked to original via `originalReference`. |

- Revenue is reportable **by vertical** — `getRevenue(window, groupBy)`.
- Refunds correctly reduce net revenue via the reversal posting.
- All postings carry both `pspReference` and `merchantReference` for full traceability.

---

## Outbox / Event Log

Ledger postings are **decoupled** from booking/payment transactions via a simple outbox
pattern:

1. A payment event (capture, refund) writes atomically to the `outbox` table in the same
   transaction.
2. The ledger service reads the outbox and applies the posting.
3. Idempotency: the outbox entry carries the `pspReference`; duplicate processing is
   a no-op.

This avoids distributed transaction concerns while keeping the ledger eventually consistent
with the payment state.

---

## Refunds

- A `Refund` is a one-to-many child of `Payment`.
- Each refund has its own `pspReference`.
- Linked to the parent payment via `originalReference` (the parent/child PSP chain).
- **Partial refunds supported.** Cannot refund more than the captured amount.
- Refund posts a reversal entry to the ledger.

---

## Amount tracking on Booking

| Field | Formula |
|-------|---------|
| `totalAmount` | Sum of all BookingLine price snapshots |
| `amountPaid` | Sum of all captures across all Payments for this Booking |
| `amountRefunded` | Sum of all Refunds across all Payments for this Booking |
| `balance` | `totalAmount − amountPaid + amountRefunded` |

**Paid = `balance == 0`.**

One Booking may have many Payments (partial payments, retried links). A Payment settles
exactly one Booking. No many-to-many.

---

## Out of scope

- Multi-currency.
- Tax calculation.
- Invoice generation.
- Multi-capture / incremental auth.
- Holds / draft postings.

---

## Contract-Drift Log

> Frozen contracts may not drift silently. Any deviation a builder must make is recorded
> here with date, the clause affected, what changed, and why. Until a row is arbitrated
> and the contract re-frozen, **the code is the source of truth for that clause.**
> This is the hotel-sim analogue of NikkFit's "Code-vs-PRD Errata," but it governs a
> *frozen contract*, so it doubles as the central-arbitration record.

| Date | Clause | Change | Reason | Arbitrated |
|------|--------|--------|--------|------------|
| —    | —      | No drift recorded. | — | — |
