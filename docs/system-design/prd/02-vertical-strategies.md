# HS-02 — Vertical Strategies

| Field            | Value                                                        |
|------------------|--------------------------------------------------------------|
| **ID**           | HS-02                                                        |
| **Title**        | Vertical Strategies                                          |
| **Version**      | 0.1                                                          |
| **Contract Status** | Draft                                                     |
| **Build Status** | Not Started                                                  |
| **Date**         | 2026-06-10                                                   |
| **Owner**        | Track B (Vertical strategies)                                |
| **Freezes**      | Strategy interface · Rooms · Spa · F&B · Events strategies   |

---

## Overview

Every vertical implements a common **strategy interface** in `core-api`. This is
where "Rooms behave this way, F&B behaves that way" lives — in one place, not in
parallel silos. The four strategies are plugged into the domain by vertical type;
the rest of the system stays uniform.

---

## The Strategy Interface

Each vertical strategy provides three concerns:

| Method | Returns | Description |
|--------|---------|-------------|
| `getAvailability(productId, window, quantity)` | `AvailabilityResult` | How many units are available in the given window |
| `calculatePrice(product, line)` | `Money` | The price for this BookingLine at booking time |
| `defaultCaptureMode()` | `CaptureMode` | `IMMEDIATE` or `MANUAL` — the payment default for this vertical |

The strategy is selected by the `vertical` field on `Product`. All business logic
that varies per vertical lives here; the booking, payment, and ledger flows are
vertical-agnostic.

---

## Per-Vertical Behaviour

### Rooms

| Concern | Behaviour |
|---------|-----------|
| Availability unit | Room / night. Capacity = number of rooms of a given category per date. |
| Pricing | Per-night rate from the Product config; multiplied by nights on the BookingLine. |
| Default capture mode | `MANUAL` — authorise card at booking; capture at checkout. |
| Notes | Partial capture supported (e.g. early checkout). |

### Spa

| Concern | Behaviour |
|---------|-----------|
| Availability unit | Therapist time slot. Capacity = available slots per treatment per time window. |
| Pricing | Per-session rate from the Product config. |
| Default capture mode | TBD — to be defined during Track B build. |
| Notes | `spa_therapist` preference (e.g. `female`) can be matched by the MCP layer. |

### F&B (Restaurants)

| Concern | Behaviour |
|---------|-----------|
| Availability unit | Covers per service period. Capacity = max covers per period. |
| Pricing | Per-cover rate; total is quantity × rate. |
| Default capture mode | `IMMEDIATE` — auth and capture together at booking. |
| Notes | Service period examples: breakfast 07:00–10:00, lunch, dinner. |

### Events

| Concern | Behaviour |
|---------|-----------|
| Availability unit | Experience capacity. Capacity = max participants per event. |
| Pricing | Per-person rate from the Product config. |
| Default capture mode | TBD — to be defined during Track B build. |
| Notes | Examples: city tour, Hyde Park horse ride. Capacity-limited. |

---

## Design rules

1. **Strategy is a plug-in.** The booking service calls the strategy interface; it does not
   branch on vertical type.
2. **`defaultCaptureMode()` is a default, not a mandate.** The caller may explicitly pass
   a different capture mode on the payment request.
3. **No strategy may add endpoints.** All capability lives in the shared API surface (HS-05).
4. **Price snapshot at booking time.** The strategy calculates price once; that value is
   stored on the BookingLine and never recalculated.

---

## Out of scope

- Dynamic pricing / yield management.
- Cross-vertical bundle discounts.
- Availability for holds/drafts (purely additive later).

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
