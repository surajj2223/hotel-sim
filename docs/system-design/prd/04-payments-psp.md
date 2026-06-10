# HS-04 — Payments & PSP Integration

| Field            | Value                                                        |
|------------------|--------------------------------------------------------------|
| **ID**           | HS-04                                                        |
| **Title**        | Payments & PSP Integration                                   |
| **Version**      | 0.1                                                          |
| **Contract Status** | Draft                                                     |
| **Build Status** | Not Started                                                  |
| **Date**         | 2026-06-10                                                   |
| **Owner**        | Track D (Payment orchestration) + Track E (payments-sim / pay-web) |
| **Freezes**      | Reference taxonomy · Payment-link flow · Webhook vocabulary · Idempotency · payments-sim · pay-web |

---

## Overview

This PRD covers the end-to-end payment flow: from creating a hosted payment link, through
the customer completing checkout on `pay-web`, to `payments-sim` firing a webhook, to
`core-api` handling it idempotently and posting to the ledger. The PSP is simulated by
`payments-sim`, which behaves like a real PSP — minting real-looking references and firing
all the webhook event codes.

---

## Reference Taxonomy (settled — see also HS-03)

| Reference | Minted by | Sent by us | Returned by PSP | Purpose |
|-----------|-----------|-----------|-----------------|---------|
| `shopperReference` | `core-api` | Yes (on link creation) | Echoed | Customer continuity across stays |
| `merchantReference` | `core-api` | Yes (on link creation) | Echoed | Reconciliation anchor per payment attempt |
| `pspReference` | `payments-sim` | No | Yes (on first webhook) | PSP transaction id |
| `paymentLinkId` | `payments-sim` | No | Yes (on link creation) | Hosted checkout link id |

- `merchantReference` is immutable per payment attempt and is the reconciliation key.
- `pspReference` is stamped onto the `Payment` record when the first webhook arrives.
- `paymentLinkId` is stored so `ops-web` can display the link and `core-api` can
  reference it in later operations.

---

## Payment-Link Flow

```
ops-web / MCP
     │  POST /payments  { shopperReference, merchantReference, amount, captureMode, … }
     ▼
core-api
     │  Creates Payment record (status: PENDING_LINK)
     │  POST /payment-links to payments-sim
     ▼
payments-sim
     │  Mints paymentLinkId (realistically formatted)
     │  Returns { paymentLinkId, checkoutUrl }
     ▼
core-api
     │  Stores paymentLinkId on Payment
     │  Returns { paymentLinkId, checkoutUrl } to caller

     ┄ customer visits pay-web (hosted checkout) ┄

pay-web
     │  Simulates customer completing payment
     ▼
payments-sim
     │  Mints pspReference
     │  Fires AUTHORISATION webhook → core-api /webhooks/payment
     │  (If IMMEDIATE: immediately fires CAPTURE webhook too)
     ▼
core-api
     │  Idempotent handler: match by merchantReference,
     │  stamp pspReference on Payment,
     │  update Payment status,
     │  post to ledger via outbox
```

---

## Webhook Event Vocabulary

| Event code | Triggered when | core-api response |
|------------|----------------|-------------------|
| `AUTHORISATION` | PSP authorises the payment | Stamp `pspReference`; status → `AUTHORISED` |
| `AUTHORISATION_FAILED` | Authorisation declined | Status → `FAILED` |
| `CAPTURE` | Payment captured (MANUAL: after explicit capture call) | Post revenue to ledger; status → `CAPTURED` |
| `CAPTURE_FAILED` | Capture attempt failed | Status → `CAPTURE_FAILED` |
| `CANCELLATION` | Auth cancelled before capture | Status → `CANCELLED`; no ledger reversal |
| `REFUND` | Refund settled | Post reversal to ledger; Refund record status → `REFUNDED` |
| `REFUND_FAILED` | Refund attempt failed | Refund record status → `REFUND_FAILED` |
| `OFFER_CLOSED` (auth expiry) | Auth expired with no capture | Status → `EXPIRED`; no ledger reversal |

All webhook payloads echo `merchantReference`, `pspReference`, and `shopperReference`.

---

## Idempotency Rules

1. Webhooks carry a `pspReference`-derived idempotency key.
2. `core-api` matches inbound webhook to a `Payment` by `merchantReference`.
3. If a webhook with the same `pspReference` and event code has already been processed,
   the handler returns `200 OK` with no state change.
4. Duplicate processing is a no-op — never double-posts to the ledger.

---

## payments-sim Behaviour

- Accepts `shopperReference` + `merchantReference` from `core-api` (stores, does not invent them).
- Mints `pspReference` (format: `PSP-…`, realistically formatted UUID-based).
- Mints `paymentLinkId` (format: `PL-…`).
- Hosts checkout at a URL returned to `core-api`.
- Fires all webhook event codes above.
- Separate Spring Boot service on port `9090`.
- Runs over real HTTP between `payments-sim` and `core-api` — this is deliberate, to
  simulate a real PSP integration.

---

## pay-web Behaviour

- A React UI on port `3001`.
- Simulates the customer's hosted checkout experience.
- On "Pay" button click: calls `payments-sim` to trigger the `AUTHORISATION` webhook.
- No real payment processing.

---

## Out of scope

- Real PSP (Adyen, Stripe, etc.).
- Real card data.
- 3DS / SCA flows.
- Multi-capture / incremental auth.
- Payment links with expiry enforcement.

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
