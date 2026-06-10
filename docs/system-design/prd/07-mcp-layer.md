# HS-07 — MCP Layer (Head 2)

| Field            | Value                                                        |
|------------------|--------------------------------------------------------------|
| **ID**           | HS-07                                                        |
| **Title**        | MCP Layer (Head 2)                                           |
| **Version**      | 0.1                                                          |
| **Contract Status** | Draft                                                     |
| **Build Status** | Deferred (Wave 2)                                            |
| **Date**         | 2026-06-10                                                   |
| **Owner**        | TBD (Wave 2)                                                 |
| **Freezes**      | Three proof capabilities · Additive-only constraint · Tool-wrapper design |

---

## Overview

The MCP server is Head 2 — a thin tool-wrapper over the same `core-api` endpoints that
`ops-web` already uses. It is the **last, lightest piece** of the system: it adds no new
logic, no new endpoints, no new persistence. It demonstrates that an MCP layer can drop
onto a complete headful system without rebuilding anything underneath.

This PRD is written now (Wave 0) to document the design intent. Implementation is Wave 2.

---

## The additive-only constraint

- **No capability may exist solely for the agent.** Every MCP tool maps 1:1 to an existing
  `core-api` endpoint that already fully serves `ops-web`.
- The MCP server calls `core-api` over HTTP — the same HTTP calls `ops-web` would make.
- No new endpoints. No bypasses. No agent-privileged paths.

---

## The three proof capabilities

These are the specific capabilities that prove the thesis of the system.

### 1. Preference-aware suggestion

The API returns availability alongside stored customer preferences.
The LLM does the matching and judgement — not the server.

> *"Mr. John Patel checks in Friday for 3 nights, wants a high-floor quiet room;
> add a couples massage Saturday afternoon and a table for two at 8."*

The agent calls:
1. `searchAvailability` — get available rooms, spa slots, F&B covers.
2. `getCustomerPreferences` — get Patel's stored preferences.
3. Proposes in chat based on matching.
4. On human confirmation: `createBooking` (single call, revalidated).

### 2. Cross-vertical orchestration

One natural-language instruction produces a room + spa + dinner folio in a single
confirmed write.

This demonstrates that the unified Booking/folio model is not just a design principle
but an operational reality: a multi-vertical folio is created atomically, not as three
separate bookings.

### 3. Financial read-back

Conversational interrogation of the ledger.

> *"Pull up everything for Mrs. Chen this stay and tell me what's unpaid."*
> *"Today's revenue split by vertical, and flag anything still pending payment."*

Calls: `getFolio`, `getRevenue`, `listUnpaidBookings`. Read-only — no human gate required.

---

## Representative agent utterances

- *"Mr. John Patel checks in Friday for 3 nights, wants a high-floor quiet room; add a
  couples massage Saturday afternoon and a table for two at 8; send a payment link for the lot."*
- *"What's open in the spa tomorrow between 2 and 5?"*
- *"Pull up everything for Mrs. Chen this stay and tell me what's unpaid."*
- *"Today's revenue split by vertical, and flag anything still pending payment."*

---

## Tool inventory (Wave 2 implementation)

| Tool | Maps to | Read / Write |
|------|---------|-------------|
| `searchAvailability` | `GET /availability` | Read |
| `searchCustomers` | `GET /customers?q=…` | Read |
| `getCustomer` | `GET /customers/{id}` | Read |
| `getCustomerPreferences` | `GET /customers/{id}/preferences` | Read |
| `getFolio` | `GET /bookings/{id}` | Read |
| `getRevenue` | `GET /reports/revenue` | Read |
| `listUnpaidBookings` | `GET /bookings?status=unpaid` | Read |
| `createCustomer` | `POST /customers` | Write (human-gated) |
| `updateCustomer` | `PUT /customers/{id}` | Write (human-gated) |
| `setCustomerPreference` | `PUT /customers/{id}/preferences/{key}` | Write (human-gated) |
| `createBooking` | `POST /bookings` | Write (human-gated) |
| `modifyBookingLine` | `PATCH /bookings/{id}/lines/{lineId}` | Write (human-gated) |
| `cancelBookingLine` | `DELETE /bookings/{id}/lines/{lineId}` | Write (human-gated) |
| `createPaymentLink` | `POST /bookings/{id}/payments` | Write (human-gated) |
| `capturePayment` | `POST /payments/{id}/capture` | Write (human-gated) |
| `cancelAuthorisation` | `POST /payments/{id}/cancel` | Write (human-gated) |
| `refundPayment` | `POST /payments/{id}/refund` | Write (human-gated) |

Stack: TBD (Wave 2). Port: TBD.

---

## Out of scope

- The MCP server implementation (Wave 2).
- Agent-specific prompt engineering.
- Conversation history / session management.
- Any capability that does not map to an existing `core-api` endpoint.

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
