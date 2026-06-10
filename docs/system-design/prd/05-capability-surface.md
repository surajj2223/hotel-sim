# HS-05 — Capability Surface (API)

| Field            | Value                                                        |
|------------------|--------------------------------------------------------------|
| **ID**           | HS-05                                                        |
| **Title**        | Capability Surface (API)                                     |
| **Version**      | 0.1                                                          |
| **Contract Status** | Draft                                                     |
| **Build Status** | Not Started                                                  |
| **Date**         | 2026-06-10                                                   |
| **Owner**        | Track A (domain interfaces) + Track F (ops-web)              |
| **Freezes**      | Reads · Writes · OpenAPI contract slice · ops-web screen inventory |

---

## Overview

This PRD defines the complete capability surface of `core-api`: every read and every
write, the OpenAPI contract references, and the ops-web screen that serves each
capability. It also documents the `ops-web` screen inventory — because `ops-web` has
no standalone PRD, its screens are listed here, preserving the "no agent-only endpoints"
symmetry.

**The symmetry rule:** every endpoint must fully serve `ops-web`. Every capability notes
the ops-web screen it serves AND the MCP tool (Wave 2) it backs.

The authoritative HTTP contract is `contracts/WAVE0_02_OPENAPI.yaml`.

---

## Reads (freely callable — no human-gate required)

| Capability | API operation | ops-web screen | MCP tool (Wave 2) |
|-----------|---------------|----------------|-------------------|
| Search availability | `GET /availability` | Booking search / New booking flow | `searchAvailability` |
| Search customers | `GET /customers?q=…` | Customer lookup sidebar | `searchCustomers` |
| Get customer | `GET /customers/{id}` | Customer detail / folio view | `getCustomer` |
| Get customer preferences | `GET /customers/{id}/preferences` | Customer detail — preferences panel | `getCustomerPreferences` |
| Get folio | `GET /bookings/{id}` | Folio / booking detail | `getFolio` |
| Get revenue | `GET /reports/revenue?window=…&groupBy=…` | Revenue dashboard | `getRevenue` |
| List unpaid bookings | `GET /bookings?status=unpaid` | Unpaid bookings report | `listUnpaidBookings` |

---

## Writes (confirm-then-commit · write-time revalidation · human-gated)

| Capability | API operation | ops-web screen | MCP tool (Wave 2) |
|-----------|---------------|----------------|-------------------|
| Create customer | `POST /customers` | Customer creation form | `createCustomer` |
| Update customer | `PUT /customers/{id}` | Customer edit form | `updateCustomer` |
| Set customer preference | `PUT /customers/{id}/preferences/{key}` | Customer preferences editor | `setCustomerPreference` |
| Delete customer preference | `DELETE /customers/{id}/preferences/{key}` | Customer preferences editor | `deleteCustomerPreference` |
| Create booking | `POST /bookings` | New booking wizard | `createBooking` |
| Modify booking line | `PATCH /bookings/{id}/lines/{lineId}` | Booking detail — edit line | `modifyBookingLine` |
| Cancel booking line | `DELETE /bookings/{id}/lines/{lineId}` | Booking detail — cancel line | `cancelBookingLine` |
| Create payment link | `POST /bookings/{id}/payments` | Booking detail — send payment link | `createPaymentLink` |
| Capture payment | `POST /payments/{id}/capture` | Booking detail — capture button | `capturePayment` |
| Cancel authorisation | `POST /payments/{id}/cancel` | Booking detail — cancel auth button | `cancelAuthorisation` |
| Refund payment | `POST /payments/{id}/refund` | Booking detail — refund panel | `refundPayment` |

---

## Human-gate mechanism

The server-side commit gate is described fully in HS-06. In brief:
- Every repercussive write requires a human-authorisation signal in the request.
- The signal can come from an `ops-web` button click or from an agent "yes" in conversation.
- `core-api` validates the signal; the caller cannot self-mint it.
- See `contracts/WAVE0_02_OPENAPI.yaml` for the header/field that carries this signal.

---

## Write-time revalidation

Every write re-checks availability and price atomically:
- If state has moved since the caller last read, the write returns `409 Conflict` with the
  current state, rather than writing stale data.
- This is the core safety mechanism. See HS-06 for the full HITL story.

---

## ops-web Screen Inventory

`ops-web` is a complete standalone operations console (Head 1). It has no standalone PRD;
its screens are inventoried here.

| Screen | Purpose | Capabilities used |
|--------|---------|-------------------|
| Customer list | Search and browse customers | `searchCustomers` |
| Customer detail | View profile, preferences, folios | `getCustomer`, `getCustomerPreferences`, `getFolio` |
| Customer create / edit | Create or update customer | `createCustomer`, `updateCustomer` |
| Booking search | Find available slots across verticals | `searchAvailability` |
| New booking wizard | Build and confirm a cross-vertical folio | `createBooking` (revalidated at submit) |
| Booking / folio detail | View folio, lines, payments, status | `getFolio` |
| Modify / cancel line | Adjust or cancel a booking line | `modifyBookingLine`, `cancelBookingLine` |
| Payment panel | Send link, capture, cancel, refund | `createPaymentLink`, `capturePayment`, `cancelAuthorisation`, `refundPayment` |
| Revenue dashboard | Revenue by vertical and date window | `getRevenue` |
| Unpaid bookings | List all bookings with outstanding balance | `listUnpaidBookings` |

---

## Out of scope

- Endpoints that exist solely for the MCP agent (none — by design).
- Agent-side tool implementation (HS-07, Wave 2).
- Authentication / authorisation beyond stubbing.

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
