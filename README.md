# Hospitality Operations Platform

A proof-of-concept multi-vertical hotel operations system demonstrating that a complete,
conventional headful system can be made headless — via an additive MCP layer — without
rebuilding anything underneath.

---

## The Thesis

> Here is a normal, complete, headful operations system — the kind that already exists
> everywhere — and an MCP layer drops onto it cleanly to make it headless, without
> rebuilding the body.

The persuasive power of this POC comes from the UI system being **real and unremarkable**.
The MCP is strictly additive: it wraps the same endpoints `ops-web` already uses. No
capability may exist solely for the agent.

**Two equal heads, one body:**

| Layer | What it is |
|---|---|
| **Body** — `core-api` | All logic, rules, and state. The single source of truth. |
| **Head 1 (headful)** — `ops-web` | A complete standalone operations console. |
| **Head 2 (headless)** — MCP server | A thin tool-wrapper over the same `core-api` endpoints. |

---

## Business Domain

Four sellable verticals managed from a single system:

- **Rooms** — room/night bookings
- **Spa** — therapist and treatment time slots
- **F&B** — restaurant covers per service period
- **Events** — capacity-limited experiences (e.g. city tours, horse rides)

Every vertical is the same thing at the domain level: a bookable resource with capacity
over time, sold to a customer, paid for, and posted to a ledger. Vertical-specific
behaviour (availability, pricing, payment capture mode) lives in per-vertical strategy
classes — not in parallel silos.

---

## Projects

| Project | Stack | Role |
|---|---|---|
| `core-api` | Spring Boot | The body — domain, persistence, payment orchestration, ledger |
| `payments-sim` | Spring Boot | Fake PSP: mints references, hosts checkout, fires webhooks |
| `ops-web` | React | Complete operations console (Head 1) |
| `pay-web` | React | Simulated hosted checkout page (triggers the PSP webhook) |
| `db` | Postgres (Docker) | Single shared instance |
| `docker-compose` | — | Wires everything; home of the smoke test |
| MCP server | TBD (Wave 2) | Head 2 — thin wrapper over `core-api` |

---

## Getting Started

```bash
# Start all services
docker-compose up

# core-api
http://localhost:8080

# ops-web
http://localhost:3000

# payments-sim
http://localhost:9090

# pay-web (hosted checkout)
http://localhost:3001
```

Health checks are available at `GET /actuator/health` on each Spring Boot service.

---

## Architecture

### Domain Model

- **Customer** — carries an immutable opaque `shopperReference` (`SHPR-…`). Never expose the raw DB id externally.
- **CustomerPreference** — flexible key/value store, cross-vertical (e.g. `floor=high`, `dietary=vegan`, `spa_therapist=female`).
- **Product** — polymorphic, keyed by vertical. A room, a massage slot, a dinner cover, and a horse ride are all Products.
- **Booking / Folio** — one customer, one or more `BookingLine`s across any verticals. Tracks `totalAmount`, `amountPaid`, `amountRefunded`, and derived `balance`. Paid means `balance == 0`.
- **Inventory** — generic capacity per product/slot, consumed by committed bookings only. No holds in this POC.

### Finance

Finance is a first-class feature, not an afterthought.

**Payment reference vocabulary (Adyen-flavoured):**

| Reference | Minted by | Meaning |
|---|---|---|
| `shopperReference` | Us | Stable customer id, sent to PSP |
| `merchantReference` | Us | Per payment attempt; reconciliation anchor |
| `pspReference` | `payments-sim` | PSP's own transaction id |
| `paymentLinkId` | `payments-sim` | Hosted payment link id |

**Capture modes** (per payment, defaulted by vertical strategy):

- `IMMEDIATE` — auth and capture together (default for F&B)
- `MANUAL` — auth now, capture later (default for Rooms). Partial capture supported; multi-capture is not.

**Ledger rules:**
- Auth ≠ revenue. Authorisation is a hold, not a financial posting.
- Capture posts revenue. For `IMMEDIATE` this happens at auth time; for `MANUAL` it happens at explicit capture.
- Cancelling an uncaptured auth produces no reversal (nothing was posted).
- Refunds are children of their Payment, linked via `originalReference`. Partial refunds supported. Refund posts a reversal entry.

### Payment Flow

```
ops-web / MCP
     │  createPaymentLink
     ▼
core-api ──────────────────► payments-sim
     │   (POST /payment-links)      │
     │                              │  returns paymentLinkId
     │◄─────────────────────────────┘
     │
     │  customer visits pay-web (hosted checkout)
     │
     ▼
payments-sim fires webhook ──► core-api
  AUTHORISATION / CAPTURE           │  idempotent handler stamps
  REFUND / CANCELLATION             │  pspReference on Payment,
                                    │  posts to ledger
```

### `core-api` Layering

```
Controllers
    │  DTOs only (entities never leave this layer)
    ▼
Application Services
    │  orchestration, write-time revalidation, commit gate
    ▼
Domain  ◄──  Per-vertical Strategy (availability / pricing / captureMode)
    │
    ▼
Repositories  ──►  Postgres
    │
    ▼
Outbox / Event Log  ──►  Ledger Service
```

### Human-in-the-Loop (MCP safety)

Writes are never autonomous:

1. **Confirm-then-commit** — the agent reads availability, proposes in chat, the operator confirms, then the agent calls one write endpoint. No draft/two-phase ceremony.
2. **Write-time revalidation** — every write atomically re-checks availability and price. If state has moved since the agent last read, the request fails loudly with the current state rather than writing stale data.
3. **Server-side commit gate** — `core-api` requires a human-authorisation signal on repercussive writes. The agent cannot self-mint this signal. Safety is enforced in the server, not by trusting the MCP to behave.
4. **Dual-channel confirmation** — a write can be authorised by a click in `ops-web` **or** a "yes" in the agent conversation. Both are legitimate. The same `createBooking` called from a button and from the agent lands identically in the ledger — that equivalence is itself part of the proof.

---

## Capability Surface

**Reads** (agent or UI calls freely):

`searchAvailability` · `searchCustomers` · `getCustomer` · `getCustomerPreferences` · `getFolio` · `getRevenue(window, groupBy)` · `listUnpaidBookings`

**Writes** (confirm-then-commit, revalidated, human-gated):

Customer CRUD + preferences · `createBooking` · `modifyBookingLine` · `cancelBookingLine` · `createPaymentLink` · `capturePayment(amount?)` · `cancelAuthorisation` · `refundPayment(amount?)`

Every endpoint must fully serve `ops-web`. No endpoint exists solely for the agent.

---

## MCP Capability Showcase

The three capabilities that prove the thesis:

1. **Preference-aware suggestion** — the API returns availability alongside stored customer preferences; the LLM does the matching. Facts from the system, judgement from the model.
2. **Cross-vertical orchestration** — one natural-language instruction produces a room + spa + dinner folio in a single confirmed write.
3. **Financial read-back** — conversational interrogation of the ledger (`getFolio`, `getRevenue`, `listUnpaidBookings`).

Representative agent utterances:
- *"Mr. John Patel checks in Friday for 3 nights, wants a high-floor quiet room; add a couples massage Saturday afternoon and a table for two at 8; send a payment link for the lot."*
- *"What's open in the spa tomorrow between 2 and 5?"*
- *"Pull up everything for Mrs. Chen this stay and tell me what's unpaid."*
- *"Today's revenue split by vertical, and flag anything still pending payment."*

---

## Delivery Waves

**Wave 0 — Foundation** *(sequential; freeze before proceeding)*
DDL + enums glossary → `core-api` OpenAPI spec → webhook/PSP event contract → scaffold repos + docker-compose + health checks.

**Wave 1 — Parallel build** *(against frozen contracts, with mocks)*

| Track | Scope |
|---|---|
| A | Core domain + persistence (domain interfaces published day one) |
| B | Vertical strategies: Rooms, Spa, F&B, Events |
| C | Ledger / finance (consumes booking events via outbox) |
| D | Payment orchestration in `core-api` (links, webhooks, idempotency) |
| E | `payments-sim` + `pay-web` |
| F | `ops-web` — complete console, built against the OpenAPI mock |

One integration owner holds docker-compose and the end-to-end smoke test.

**Wave 2 — Integration & MCP**
Full smoke test (create customer → search → cross-vertical folio → confirm → pay → see postings), then the MCP server as the last, lightest piece.

---

## Patterns & Practices

**Applied:** hexagonal-ish layering · strategy pattern per vertical · DTOs at every boundary (entities never exposed) · idempotent webhook handling · outbox/event log for decoupled ledger postings · write-time revalidation on all writes · server-side commit gate.

**Deliberately out of scope (POC):** microservice-per-vertical · event bus / Kafka · CQRS · real auth (stubbed) · real money · multi-currency · holds/drafts · multi-capture / incremental auth.

---

## Key Design Decisions

- 4 verticals behind a unified Booking/folio; polymorphic Product; per-vertical strategies.
- `shopperReference` is immutable and opaque; reconciliation anchors on `merchantReference`; PSP mints `pspReference` and `paymentLinkId`.
- One booking → many payments; partial payments supported; refunds are children of a payment via `originalReference`.
- Partial capture supported; multi-capture and incremental-auth are not.
- Ledger posts on capture, not auth.
- `ops-web` is a complete product; MCP is strictly additive; no agent-only endpoints.

---

## Next Steps

The immediate next action is writing the frozen Wave 0 artifacts:

1. DDL + enums/glossary (requires sign-off)
2. `core-api` OpenAPI spec (all reads and writes)
3. Webhook / PSP event contract

See `project-brief.md` for the full project charter.