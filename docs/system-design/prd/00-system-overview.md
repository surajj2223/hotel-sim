# HS-00 — System Overview (Living)

| Field            | Value                                              |
|------------------|----------------------------------------------------|
| **ID**           | HS-00                                              |
| **Title**        | System Overview                                    |
| **Version**      | 0.1                                                |
| **Contract Status** | Frozen                                          |
| **Build Status** | In Progress                                        |
| **Date**         | 2026-06-10                                         |
| **Owner**        | Project Arbiter                                    |
| **Freezes**      | Thesis · Two-head model · Project map · Verticals  |

---

## Overview

The Hospitality Operations Platform is a proof-of-concept multi-vertical hotel
operations system. Its thesis is the reverse of "AI-native":

> *Here is a normal, complete, headful operations system — the kind that already
> exists everywhere — and an MCP layer drops onto it cleanly to make it headless,
> without rebuilding anything underneath.*

Persuasive power comes from the UI system being **real and unremarkable**. The MCP
is strictly additive: it wraps the same endpoints `ops-web` already uses. No
capability may exist solely for the agent.

---

## The Two-Head Architecture

One body, two equal heads:

| Layer | Project | What it is |
|-------|---------|------------|
| **Body** | `core-api` | All logic, rules, and state. The single source of truth. Spring Boot. |
| **Head 1 (headful)** | `ops-web` | A complete standalone operations console. React. |
| **Head 2 (headless)** | MCP server | A thin tool-wrapper over the same `core-api` endpoints. (Wave 2.) |

**Acceptance test for every endpoint:** can `ops-web` build a complete screen on it?
If yes, the MCP will be fine. No capability may exist solely for the agent.

---

## Business Domain

Four sellable verticals managed from a single system:

| Vertical | What is sold |
|----------|-------------|
| **Rooms** | Room / night bookings |
| **Spa** | Therapist and treatment time slots |
| **F&B** | Restaurant covers per service period |
| **Events** | Capacity-limited experiences (e.g. city tours, horse rides) |

Every vertical is the same thing at the domain level: a bookable resource with
capacity over time, sold to a customer, paid for, and posted to a ledger.
Vertical-specific behaviour (availability, pricing, payment capture mode) lives in
per-vertical strategy classes — not in parallel silos.

---

## Projects

| Project | Stack | Role |
|---------|-------|------|
| `core-api` | Spring Boot | The body — domain, persistence, payment orchestration, ledger |
| `payments-sim` | Spring Boot | Fake PSP: mints references, hosts checkout, fires webhooks |
| `ops-web` | React | Complete operations console (Head 1) |
| `pay-web` | React | Simulated hosted checkout page (triggers the PSP webhook) |
| `db` | Postgres (Docker) | Single shared instance |
| `docker-compose` | — | Wires everything; home of the smoke test |
| MCP server | TBD (Wave 2) | Head 2 — thin wrapper over `core-api` |

---

## Patterns & Practices

**Applied:**
- Hexagonal-ish layering: Controllers → Application Services → Domain → Repositories
- Strategy pattern per vertical (availability, pricing, capture default)
- DTOs at every boundary (entities never exposed)
- Idempotent webhook handling
- Outbox / event log for decoupled ledger postings
- Write-time revalidation on all writes
- Server-side commit gate

**Deliberately out of scope (POC):**
- Microservice-per-vertical
- Event bus / Kafka
- CQRS
- Real authentication (stubbed)
- Real money / multi-currency
- Holds / drafts
- Multi-capture / incremental auth

---

## Key Design Decisions (settled)

1. 4 verticals behind a unified Booking/folio; polymorphic Product; per-vertical strategies.
2. `shopperReference` is immutable and opaque; reconciliation anchors on `merchantReference`;
   PSP mints `pspReference` and `paymentLinkId`.
3. One booking → many payments; partial payments supported; refunds are children of a
   payment via `originalReference`.
4. Partial capture supported; multi-capture and incremental-auth are not.
5. Ledger posts on capture, not auth.
6. `ops-web` is a complete product; MCP is strictly additive; no agent-only endpoints.

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
