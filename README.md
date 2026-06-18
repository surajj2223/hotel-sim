# Hospitality Operations Platform (`hotel-sim`)

A proof-of-concept multi-vertical hotel operations system, built to demonstrate that a
complete, conventional **headful** system can later be made **headless** — via an additive
MCP layer — without rebuilding anything underneath.

> **Status:** Wave 0 (contracts) is frozen. Wave 1 is in progress — **Stage 2 complete**
> (tagged `stage-2-complete`; close-out: balance split into `customerOwes`/`netRevenue`,
> folio/line completion lifecycle); Stages 3–8 remain. `core-api` and a standalone `payments-sim` PSP
> are built and talk to each other over real HTTP + signed webhooks. The MCP server,
> `ops-web`, and a richer customer checkout are not built yet. Wave 1 is delivered in
> Stages — see [Delivery Waves](#delivery-waves) and
> [HS-08](docs/system-design/prd/08-delivery-plan.md). See
> [What actually runs today](#what-actually-runs-today) and
> [`docs/RUNNING.md`](docs/RUNNING.md) for the ground truth — this README also describes
> the intended end-state, which is clearly marked where it differs.

---

## The Thesis

> Here is a normal, complete, headful operations system — the kind that already exists
> everywhere — and an MCP layer drops onto it cleanly to make it headless, without
> rebuilding the body.

The persuasive power of this POC comes from the underlying system being **real and
unremarkable**. The MCP is strictly additive: it wraps the same endpoints the operations
console already uses. **No capability may exist solely for the agent.**

**Two equal heads, one body:**

| Layer | What it is | Built? |
|---|---|---|
| **Body** — `core-api` | All logic, rules, and state. The single source of truth. | ✅ Built — Wave 1 @ Stage 2 (money loop, SPA, scoped allocation, balance split + folio completion lifecycle shipped; `stage-2-complete` tag) |
| **Head 1 (headful)** — `ops-web` | A complete standalone operations console. | ⏳ Not yet built |
| **Head 2 (headless)** — MCP server | A thin tool-wrapper over the same `core-api` endpoints. | ⏳ Wave 2 |

---

## What actually runs today

The repository currently contains **two Spring Boot services**, each with its own Postgres,
wired by `docker-compose.yml`:

| Service | Port | What it does today |
|---|---|---|
| `core-api` | `8080` | Customers + preferences, ROOM availability, bookings/folios, payment orchestration, inbound webhook handling, outbox → ledger postings. |
| `payments-sim` | `8081` | A **stateful fake PSP**: mints `pspReference`/`paymentLinkId`, persists payment/refund state, and emits HMAC-signed webhooks back to `core-api`. |
| `db` (core) | `5432` | Postgres 16 for `core-api`. Flyway-managed; **schema-only, no seed data**. |
| `payments-sim-db` | `5433` | Separate Postgres 16 for `payments-sim` (RX-001: the PSP owns its own state). |

**Not in the tree yet:** `ops-web`, the MCP server, and a customer-facing checkout page.
An earlier design had a separate `pay-web` React app and a single shared database; both
were retired during Feature 2 planning — see
[`contracts/refactor-x/RX-001-psp-direction-and-statefulness.md`](contracts/refactor-x/RX-001-psp-direction-and-statefulness.md).

### Live endpoints (`core-api`, base `http://localhost:8080`)

**Reads / writes for the booking lifecycle:**

| Endpoint | Method | Purpose |
|---|---|---|
| `/customers` | POST | Create a customer (server mints `shopperReference`) |
| `/customers/{id}` | GET | Fetch one customer |
| `/customers/{id}/preferences/{key}` | PUT | Upsert one preference value |
| `/availability` | GET | Availability + price; resolves any registered vertical (ROOM and SPA wired — SPA returns `spaAttributes`; FNB/EVENT return 400 until registered) |
| `/bookings` | POST | Open an empty folio for a customer |
| `/bookings/{id}/lines` | POST | Add a line (revalidates availability + price, recomputes totals) |
| `/bookings/{id}` | GET | Read the folio with lines and derived amounts |

**Payments (async; the webhook is the authoritative completion signal):**

| Endpoint | Method | Purpose |
|---|---|---|
| `/bookings/{id}/payments` | POST | Create a payment link for a booking (human-gated) |
| `/bookings/{id}/payments` | GET | List a booking's payments |
| `/payments/{id}` | GET | Read one payment |
| `/payments/{id}/capture` | POST | Capture (full/partial); returns `202` |
| `/payments/{id}/cancel` | POST | Cancel an uncaptured authorisation; returns `202` |
| `/payments/{id}/refunds` | POST | Refund (full/partial); returns `202` |
| `/webhooks/psp` | POST | Inbound PSP events; the authoritative state-transition signal |

Repercussive writes require a human-authorisation header, `X-Human-Auth` (presence-only in
the POC — not cryptographically enforced). Inbound webhooks are authenticated by
`X-PSP-Signature` (HMAC), not human-gated.

> **Getting started:** the full local run guide — IDE vs Docker, the required DB seed step,
> and the curl happy-path — lives in **[`docs/RUNNING.md`](docs/RUNNING.md)**. Quick check:
> ```bash
> docker compose up -d db payments-sim-db
> cd core-api && ./gradlew bootRun        # then: curl localhost:8080/actuator/health
> ```

---

## Business Domain

Four sellable verticals managed from a single system:

- **Rooms** — room/night bookings *(wired end-to-end)*
- **Spa** — therapist and treatment time slots *(availability wired end-to-end — Stage 2; returns `spaAttributes`)*
- **F&B** — restaurant covers per service period
- **Events** — capacity-limited experiences (e.g. city tours, horse rides)

Every vertical is the same thing at the domain level: a bookable resource with capacity
over time, sold to a customer, paid for, and posted to a ledger. Vertical-specific
behaviour (availability, pricing, payment capture mode) lives in per-vertical **strategy**
classes — not in parallel silos. The `Product` hierarchy (`ProductRoom`, `ProductSpa`,
`ProductFnb`, `ProductEvent`) already exists; `RoomStrategy` and `SpaStrategy` are
registered, and `/availability` resolves any registered vertical via
`VerticalStrategyRegistry` (SPA returns `spaAttributes`). FNB/EVENT return 400 until their
strategies register.

---

## Architecture

### Domain Model

- **Customer** — carries an immutable opaque `shopperReference` (`SHPR-…`); the raw DB id is never exposed externally.
- **CustomerPreference** — flexible key/value store, cross-vertical (e.g. `floor=high`, `dietary=vegan`, `spa_therapist=female`).
- **Product** — polymorphic (`JOINED` inheritance), keyed by vertical. A room, a massage slot, a dinner cover, and a horse ride are all Products.
- **Booking / Folio** — one customer, one or more `BookingLine`s across any verticals. Tracks `totalAmount`, `amountPaid`, `amountRefunded`, and a derived `balance` (`booking_balance` is a SQL **view**, not a table). **Paid means `balance == 0`**, not a boolean.
- **Inventory** — generic capacity per product/slot, consumed by committed bookings only. **No holds/drafts** in this POC.

> **Known limitation:** the availability check is a non-locking read-decide-insert with a
> TOCTOU race (documented in `KNOWN_LIMITATION_OVERBOOKING.md`). Single-threaded manual
> testing masks it; it is flagged, not fixed, by deliberate choice.

### Finance (first-class)

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
- **Capture posts revenue.** `IMMEDIATE` posts at auth time; `MANUAL` at explicit capture.
- Cancelling an uncaptured auth produces no reversal (nothing was posted).
- Refunds are children of their Payment, linked via `originalReference`; partial refunds supported; a refund posts a reversal entry.
- Money is always **BIGINT minor units + currency code** — never floats.

Ledger postings are decoupled from booking writes through an **outbox / event log**
(`OutboxProcessor` → `LedgerService`), so revenue attribution survives even if a posting is
retried.

### Payment Flow

```
ops-web / MCP (future)
     │  createPaymentLink (POST /bookings/{id}/payments, X-Human-Auth)
     ▼
core-api ───────────────────────────────► payments-sim  (:8081)
     │   POST /payment-links                    │  mints paymentLinkId + pspReference
     │◄──────────────────────────────────────────┘
     │
     │  capture / cancel / refund  →  202 (async, fire-and-forget)
     │
payments-sim fires signed webhook ──────► core-api  POST /webhooks/psp
  AUTHORISATION / CAPTURE / CANCELLATION        │  X-PSP-Signature (HMAC) verified,
  REFUND (+ *_FAILED)                            │  idempotent handler stamps pspReference,
                                                 │  drives state transition, posts to ledger
```

### `core-api` Layering

```
Controllers          DTOs only (entities never leave this layer)
    ▼
Application Services orchestration, write-time revalidation, X-Human-Auth commit gate
    ▼
Domain  ◄── Per-vertical Strategy (availability / pricing / captureMode)
    ▼
Repositories ──► Postgres
    ▼
Outbox / Event Log ──► Ledger Service
```

### Human-in-the-Loop (MCP safety)

Writes are never autonomous. The same four mechanisms protect a click in a future
`ops-web` and a "yes" in a future agent conversation identically:

1. **Confirm-then-commit** — read availability, propose, operator confirms, one write endpoint is called. No draft/two-phase ceremony.
2. **Write-time revalidation** — every write atomically re-checks availability and price; if state moved, it fails loudly (`409`) with current state rather than writing stale data.
3. **Server-side commit gate** — repercussive writes require `X-Human-Auth` (`428` if absent). The agent cannot self-mint this; safety lives in the server, not in trusting the MCP.
4. **Dual-channel confirmation** — a write can be authorised from a UI button **or** an agent conversation. Both are legitimate; the same `createBooking` lands identically in the ledger.

---

## Capability Surface (intended end-state)

**Reads** (agent or UI call freely):
`searchAvailability` · `searchCustomers` · `getCustomer` · `getCustomerPreferences` · `getFolio` · `getRevenue(window, groupBy)` · `listUnpaidBookings`

**Writes** (confirm-then-commit, revalidated, human-gated):
Customer CRUD + preferences · `createBooking` · `modifyBookingLine` · `cancelBookingLine` · `createPaymentLink` · `capturePayment(amount?)` · `cancelAuthorisation` · `refundPayment(amount?)`

Every endpoint must fully serve `ops-web`. No endpoint exists solely for the agent.
*(Some reads above — revenue rollups, unpaid lists, multi-vertical search — are part of the
target surface, not all live yet; see the live-endpoint table above for what's wired today.)*

---

## MCP Capability Showcase (Wave 2 goal)

The three capabilities that will prove the thesis:

1. **Preference-aware suggestion** — the API returns availability alongside stored preferences; the LLM does the matching. Facts from the system, judgement from the model.
2. **Cross-vertical orchestration** — one natural-language instruction produces a room + spa + dinner folio in a single confirmed write.
3. **Financial read-back** — conversational interrogation of the ledger.

Representative agent utterances:
- *"Mr. John Patel checks in Friday for 3 nights, wants a high-floor quiet room; add a couples massage Saturday afternoon and a table for two at 8; send a payment link for the lot."*
- *"What's open in the spa tomorrow between 2 and 5?"*
- *"Pull up everything for Mrs. Chen this stay and tell me what's unpaid."*
- *"Today's revenue split by vertical, and flag anything still pending payment."*

---

## Projects

| Project | Stack | Role | State |
|---|---|---|---|
| `core-api` | Spring Boot 3.x, Java 21 | The body — domain, persistence, payment orchestration, ledger | Built (Wave 1 @ Stage 2) |
| `payments-sim` | Spring Boot 3.x, Java 21 | Stateful fake PSP: mints references, persists state, fires signed webhooks | Built (Wave 1 @ Stage 2) |
| `db`, `payments-sim-db` | Postgres 16 (Docker) | One instance per service (RX-001) | Built |
| `docker-compose` | — | Wires services; home of the smoke test | Built |
| `ops-web` | React | Complete operations console (Head 1) | Not started |
| MCP server | TBD | Head 2 — thin wrapper over `core-api` | Wave 2 |

---

## Delivery Waves

**Wave 0 — Foundation** *(frozen)*
DDL + enums glossary → `core-api` OpenAPI spec → webhook/PSP event contract → scaffold +
compose + health checks. Frozen artifacts live under `contracts/`; the **Freeze Ledger**
(`WAVE0_00_OVERVIEW.md §1b`) is the single authoritative status source. Frozen decisions
evolve only via append-only `RX-` records, never in-place edits.

**Wave 1 — Parallel build** *(in progress — delivered in Stages)*
Core domain + persistence · vertical strategies · ledger/finance (outbox-driven) · payment
orchestration · `payments-sim` · `ops-web`. Wave 1 is delivered as a Stage march:
**Stage 1** (book a room) and **Stage 2** (get paid — the money loop, SPA availability,
scoped cross-vertical allocation; close-out: balance correctly split into customer-receivable
vs net-revenue, and the folio/line completion lifecycle) are complete (tagged `stage-2-complete`); **Stages 3–8** remain.
See [HS-08 — Delivery Plan](docs/system-design/prd/08-delivery-plan.md) for the full
Stage↔Wave map.

**Wave 2 — Integration & MCP**
Full smoke test (create customer → search → cross-vertical folio → confirm → pay → see
postings), then the MCP server as the last, lightest piece.

---

## Patterns & Practices

**Applied:** hexagonal-ish layering · strategy pattern per vertical · DTOs at every boundary
(entities never exposed) · idempotent webhook handling · outbox/event log for decoupled
ledger postings · write-time revalidation on all writes · server-side commit gate ·
**contract-first discipline** (no implementation before a FROZEN requirement ID exists;
deviations are flagged, never silently fixed).

**Deliberately out of scope (POC):** microservice-per-vertical · event bus / Kafka · CQRS ·
real auth (stubbed) · real money · multi-currency · holds/drafts · multi-capture /
incremental auth.

---

## Repository Map

```
core-api/        Spring Boot — the body (domain, payments, ledger, web)
payments-sim/    Spring Boot — stateful fake PSP
contracts/       Frozen Wave 0 artifacts (WAVE0_0X_*) + refactor-x/ (RX-*) deltas
docs/
  RUNNING.md             Local run guide (authoritative for "how do I start this?")
  system-design/         PRD site (prd/*.md + interactive HTML)
  runnable-postman-collection/   Postman collection + seed-products.sql
docker-compose.yml       Wires both services + their databases
CLAUDE.md                Guardrails and named traps for agent sessions
```

---

## Key Design Decisions

- 4 verticals behind a unified Booking/folio; polymorphic `Product` (`JOINED`); per-vertical strategies.
- `shopperReference` is immutable and opaque; reconciliation anchors on `merchantReference`; PSP mints `pspReference` / `paymentLinkId`.
- One booking → many payments; partial payments supported; refunds are children of a payment via `originalReference`.
- Partial capture supported; multi-capture and incremental-auth are not.
- **Ledger posts on capture, not auth.**
- Payments are **async**: capture/cancel/refund return `202`; the signed webhook is the authoritative completion signal. No synchronous state mutation on those endpoints.
- Confirm-then-commit + write-time revalidation + server-side commit gate; **no holds/drafts**.
- `payments-sim` owns its own Postgres and is the named source of inbound webhook events (RX-001).
- `ops-web` is a complete product; MCP is strictly additive; no agent-only endpoints.

---

See [`contracts/project-brief.md`](contracts/project-brief.md) for the full project charter,
and [`docs/RUNNING.md`](docs/RUNNING.md) to run it locally.