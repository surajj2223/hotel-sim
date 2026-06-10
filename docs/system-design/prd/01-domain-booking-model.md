# HS-01 — Domain & Booking Model

| Field            | Value                                                        |
|------------------|--------------------------------------------------------------|
| **ID**           | HS-01                                                        |
| **Title**        | Domain & Booking Model                                       |
| **Version**      | 0.1                                                          |
| **Contract Status** | Draft                                                     |
| **Build Status** | Not Started                                                  |
| **Date**         | 2026-06-10                                                   |
| **Owner**        | Track A (Core domain + persistence)                          |
| **Freezes**      | Customer · Product (polymorphic) · Booking/Folio · BookingLine · Inventory |

---

## Overview

This PRD defines the unified domain abstraction that underpins all four verticals.
Every vertical — Rooms, Spa, F&B, Events — is a bookable resource with capacity over
time, sold to a customer, paid for, and posted to a ledger. This PRD is the conceptual
model; the authoritative DDL is `contracts/WAVE0_01_SCHEMA.sql`.

---

## Core Entities

### Customer

- Identity and contact information.
- Carries an **immutable, opaque** `shopperReference` (format: `SHPR-…`), minted once
  and never changed.
- The `shopperReference` is sent to the PSP for payment continuity; it is never the raw
  database id.
- Exposed externally only via DTOs — the raw entity never leaves the service layer.

### CustomerPreference

- Flexible key/value store, cross-vertical.
- Examples: `floor=high`, `dietary=vegan`, `spa_therapist=female`.
- Used by the MCP layer (Wave 2) for preference-aware availability suggestion:
  the API returns availability alongside stored preferences; the LLM does the matching.

### Product (polymorphic)

- Keyed by `vertical` enum: `ROOMS`, `SPA`, `FB`, `EVENTS`.
- Holds vertical-specific configuration (e.g. room category, treatment duration,
  cover count, experience capacity).
- A Deluxe Room, a 60-min massage, a dinner slot, and a horse ride are all Products —
  same abstraction, different vertical strategy.

### Booking / Folio

- One `Customer` + one or more `BookingLine`s.
- A single booking can span all four verticals (room + spa + dinner = one folio).
- Amount fields carried on the Booking:

| Field | Type | Meaning |
|-------|------|---------|
| `totalAmount` | Money | Sum of all BookingLine prices (snapshots) |
| `amountPaid` | Money | Sum of all captured payments |
| `amountRefunded` | Money | Sum of all refunds |
| `balance` | Derived | `totalAmount − amountPaid + amountRefunded` |

- **Paid** means `balance == 0`. There is no boolean `isPaid` field.

### BookingLine

- One line per product/slot combination within the booking.
- Fields: `Product` reference, `startTime`, `endTime` (or date for room nights),
  `quantity`, **price snapshot** (the price at booking time, immutable).
- Status: `CONFIRMED`, `CANCELLED`.
- Committed bookings consume inventory; cancellations release it.

### Inventory

- Generic capacity per product/slot (e.g. 3 rooms of type X on date Y, 2 spa slots at 14:00).
- **Consumed by committed bookings only.** No holds/drafts in this POC.
- Per-vertical strategies interpret capacity rules (see HS-02).

---

## Relationships

```
Customer
  └─ has many ──► Booking (Folio)
                     └─ has many ──► BookingLine
                                         └─ references ──► Product
                                                               └─ has ──► Inventory

Booking
  └─ has many ──► Payment
                     └─ has many ──► Refund
```

---

## Invariants

1. `shopperReference` is immutable once minted. Never update it.
2. A `BookingLine` price snapshot is fixed at booking time. Price changes after
   booking do not affect existing lines.
3. Inventory is decremented only when a `BookingLine` moves to `CONFIRMED`.
   Cancellation returns the capacity immediately.
4. `balance == 0` is the canonical "paid" signal. No redundant boolean.
5. A Booking may have zero Payments (unpaid) — tracked by `listUnpaidBookings`.
6. A Payment settles exactly one Booking. No many-to-many payment↔booking.

---

## Out of scope

- Holds / drafts (inventory reservation with TTL). The availability seam is kept
  clean so holds are purely additive later.
- Multi-currency. All amounts in a single configured currency for the POC.
- Customer authentication beyond a stub.

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
