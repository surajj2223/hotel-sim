# Hospitality Operations Platform — Project Charter

> A POC simulating a multi-vertical hotel operations system, built so that a complete
> *headful* system can later be *trimmed to headless* via an additive MCP layer.
> This document is our shared memory of **what** we're building and **why** — not a
> brick-by-brick implementation spec.

---

## 1. The Business

A hotel business running four sellable verticals, managed centrally through one system:

- **Rooms** — room/night booking
- **Spa** — therapist/treatment time slots
- **F&B (Restaurants)** — covers per service period
- **Events** — experiences with limited capacity (e.g. London tour, Hyde Park horse ride)

Central management spans: bookings across all verticals, customers and their preferences,
payments and transactions, and financial postings (ledger).

---

## 2. What We're Proving (the thesis)

The POC's punchline is **not** "we built an AI-native system." It is the reverse:

> *Here is a normal, complete, headful operations system — the kind that already exists
> everywhere — and an MCP layer drops onto it cleanly to make it headless, without
> rebuilding the body.*

Persuasive power comes from the headful system being **real and unremarkable**. The MCP
must be **strictly additive** over the same capability surface the UI already uses.

### Two equal heads on one body
- **Body:** `core-api` holds *all* logic, rules, and state.
- **Head 1 (headful):** `ops-web`, a complete standalone operations console.
- **Head 2 (headless):** the MCP server, a thin wrapper over the *same* endpoints.

**Acceptance test for every endpoint:** can `ops-web` build a complete screen on it?
If yes, the MCP will be fine. **No capability may exist solely for the agent.**

---

## 3. The MCP End Goal

The MCP user is **operations staff**, not end customers. It collapses multi-step,
multi-vertical operations into expressed intent. Representative utterances:

- "Mr. John Patel checks in Friday for 3 nights, wants a high-floor quiet room; add a
  couples massage Saturday afternoon and a table for two at 8; send a payment link for
  the lot."
- "What's open in the spa tomorrow between 2 and 5?"
- "Pull up everything for Mrs. Chen this stay and tell me what's unpaid."
- "Today's revenue split by vertical, and flag anything still pending payment."

Three capabilities are the real proof:
1. **Preference-aware suggestion** — capability returns availability + stored prefs;
   the LLM does the matching/judgement. (Facts from the system, judgement from the model.)
2. **Cross-vertical orchestration** — one instruction composes a room + spa + dinner into
   a single folio.
3. **Financial read-back** — conversational interrogation of the ledger.

---

## 4. Human-in-the-Loop

The MCP performs **writes**, but a human confirms before anything with repercussions.

**Mechanism (after deliberately simplifying):**
- **Confirm-then-commit** — agent reads availability, proposes in chat, human confirms,
  agent calls a single write endpoint. No draft/two-phase ceremony.
- **Write-time revalidation** — every write re-checks availability and price atomically;
  if state moved since the agent last read, it **fails loudly** with current state rather
  than writing stale data. This is the core safety mechanism.
- **Server-side commit gate** — `core-api` requires a human-authorisation signal on
  repercussive writes that the agent **cannot self-mint**. Safety lives in the server, not
  in the MCP behaving well.
- **Dual-channel confirmation** — the same write can be confirmed by a click in `ops-web`
  *or* a "yes" in an agent conversation. Both are legitimate human authorisation.
  Demonstrating the *same* `createBooking` from a button and from the agent, landing
  identically in the ledger, is itself a slice of the proof.

**Explicitly deferred:** soft **holds / drafts** (TTL'd inventory reservation). Not built
in the POC. The availability seam is kept clean so holds are purely additive later — a
hold becomes "another thing that consumes availability."

---

## 5. Domain Model (conceptual)

Unified abstraction: every vertical is *a bookable resource with capacity over time, sold
to a customer, paid for, and posted to a ledger.* Differences live in per-vertical
availability/pricing, not in parallel silos.

- **Customer** — identity + contact; carries an immutable, opaque **`shopperReference`**
  (`SHPR-…`), one-to-one with the customer, never the raw id.
- **CustomerPreference** — flexible, cross-vertical (e.g. `floor=high`, `dietary=vegan`,
  `spa_therapist=female`).
- **Product (polymorphic)** — keyed by `vertical`, with vertical-specific config. A Deluxe
  Room, a 60-min massage, a dinner slot, a horse ride are all Products.
- **Booking / Folio** — a customer + one or more **BookingLines**, each line referencing a
  Product, time window, quantity, and a **price snapshot**. One booking can span verticals
  (room + spa + dinner = one folio). Carries amount fields (see Finance).
- **Inventory / Availability** — generic capacity per product/slot; consumed by committed
  bookings only (no holds in POC). Per-vertical **strategies** interpret it.

### Per-vertical strategies
Each vertical implements a common strategy interface supplying:
- availability calculation, pricing, **and `defaultCaptureMode()`** (see Finance).
This is where "Rooms behave this way, F&B behaves that way" lives — in one place.

---

## 6. Finance (first-class)

Finance/ledger is a **first-class showcase feature**, not credibility dressing.

### Payment reference taxonomy (Adyen-flavoured)
- **`shopperReference`** — stable customer identifier (ours, opaque, on Customer).
- **`merchantReference`** — our reference per **payment attempt** (we send it).
- **`pspReference`** — the PSP's own transaction id (minted by `payments-sim`, returned).
- **`paymentLinkId`** — PSP's id for a hosted payment link.
- Reconciliation anchors on **`merchantReference`**; customer continuity on `shopperReference`.

### Payments & relationships
- **One Booking → many Payments** (partial payments, retried links). A Payment settles
  exactly one Booking. (No payment↔booking many-to-many.)
- Booking tracks amounts as first-class: `totalAmount`, `amountPaid`, `amountRefunded`,
  derived `balance`. **"Paid" = `balance == 0`**, not a boolean.

### Capture modes (per-payment, vertical-defaulted)
- **`IMMEDIATE`** — auth-and-capture together (e.g. F&B).
- **`MANUAL`** — auth now, capture later (e.g. Rooms: hold a card, capture at checkout).
- Default supplied by the vertical strategy, but explicit and overridable on the payment.
- **Partial capture IN** (authorise £600, capture £540, release the rest).
- **Multi-capture and incremental-auth OUT** (one auth → at most one capture).

### Refunds
- **Refund** is a one-to-many child of Payment; each refund has its own `pspReference`
  linked to the original via **`originalReference`** (the parent/child chain).
- Partial refunds supported; cannot refund more than captured.

### Ledger rules
- **Auth ≠ revenue.** An authorisation is a hold, not a financial event.
- **Capture posts revenue.** IMMEDIATE posts at auth+capture; MANUAL posts at capture time.
- Cancelling an uncaptured auth → no reversal (nothing was posted).
- Refund → reversal posting (its own posting class), traceable via `originalReference`.
- Postings carry `pspReference` / `merchantReference` so finance reads trace through to the
  PSP transaction. Revenue is reportable **by vertical**; refunds correctly reduce net.

---

## 7. Systems / Projects

| Project        | Stack            | Role |
|----------------|------------------|------|
| `core-api`     | Spring Boot      | The body — all logic, domain, persistence, payment orchestration, ledger. What the MCP later wraps. |
| `payments-sim` | Spring Boot      | Fake PSP. Mimics PSP reference vocabulary + event codes; hosts checkout; fires webhooks. Separate on purpose, so `core-api` integrates over real HTTP + webhooks. |
| `ops-web`      | React            | Complete standalone operations console (Head 1). |
| `pay-web`      | React            | Simulated hosted checkout the customer "pays" on, triggering the webhook. |
| `db`           | Postgres (Docker)| Single instance. |
| (compose)      | docker-compose   | Wires everything; home of the end-to-end smoke test. |
| MCP server     | (Wave 2)         | Head 2 — thin additive tool-wrapper over `core-api` endpoints. |

> ⚠️ **The `pay-web` and `db | Postgres (Docker) | Single instance.` rows above are superseded in part by [RX-001](refactor-x/RX-001-psp-direction-and-statefulness.md)** (see Freeze Ledger `WAVE0_00 §1b`). Original text preserved; do not build against the superseded portion without checking the ledger.

### `payments-sim` behaviour
- Accepts `shopperReference` + `merchantReference` (stores, does not invent them).
- **Mints** `pspReference` and `paymentLinkId` (realistically formatted).
- Emits webhooks echoing all references + event vocabulary:
  `AUTHORISATION`, `CAPTURE` / `CAPTURE_FAILED`, `CANCELLATION`,
  `REFUND` / `REFUND_FAILED`, plus auth-expiry.
- Webhook carries idempotency key (pspReference-derived); `core-api` matches inbound to a
  Payment by `merchantReference`, stamps the returned `pspReference`, handles idempotently.

---

## 8. Patterns & Practices

**Use:** hexagonal-ish layering (controllers → application services → domain →
repositories); **Strategy pattern** per vertical (availability, pricing, capture default);
**DTOs at every boundary** (never expose entities); **idempotent webhook handling**;
a simple **outbox / event log** so ledger postings are decoupled from booking;
write-time revalidation on all writes; server-side commit gate.

**Deliberately skip (stay POC):** microservice-per-vertical; event bus / Kafka; CQRS; real
auth (stub only); real money; multi-currency; scaling concerns; holds/drafts;
auth-then-delayed-capture beyond the single MANUAL capture; multi-capture; incremental auth.

---

## 9. Capability Surface (shape, not signatures)

**Reads (agent calls freely):**
`searchAvailability`, `searchCustomers` / `getCustomer` / `getCustomerPreferences`,
`getFolio`, `getRevenue(window, groupBy)`, `listUnpaidBookings`.

**Writes (confirm-then-commit, revalidate, human-gated):**
customer CRUD + preferences; `createBooking` / `modifyBookingLine` / `cancelBookingLine`;
`createPaymentLink`; `capturePayment(amount?)`; `cancelAuthorisation`; `refundPayment(amount?)`.

Every write revalidates at write time; every repercussive write requires the server-side
human-authorisation signal; every endpoint must fully serve `ops-web`.

---

## 10. Delivery Workflow (waves)

Principle: **front-load the contracts** so parallel agents code against frozen seams, not
each other. No agent owns a contract; contract changes are arbitrated centrally and
propagated.

- **Wave 0 — Foundation (sequential; FREEZE before proceeding).**
  DDL + enums/glossary; `core-api` OpenAPI spec (all reads + writes); webhook/PSP event
  contract; scaffold repos + docker-compose + health checks. *This is the gate.*

- **Wave 1 — Parallel build (against frozen contracts, with mocks):**
  - A. Core domain + persistence (publishes domain interfaces early)
  - B. Vertical strategies (Rooms / Spa / F&B / Events)
  - C. Ledger / finance (consumes booking events via outbox)
  - D. Payment orchestration in `core-api` (links, webhooks, idempotency)
  - E. `payments-sim` + `pay-web`
  - F. `ops-web` — **a complete console**, built against the OpenAPI mock
  - Light sequencing: A's interfaces land day one; B/C/D/E/F then run flat out.
  - One **integration owner** holds compose + the end-to-end smoke test.

  > ⚠️ **Package E above is superseded in part by [RX-001](refactor-x/RX-001-psp-direction-and-statefulness.md)** (see Freeze Ledger `WAVE0_00 §1b`). Original text preserved; do not build against the superseded portion without checking the ledger.

- **Wave 2 — Integration & MCP:**
  full smoke test (create customer → search → cross-vertical folio → confirm in tray/chat →
  pay → see postings), then the MCP server as the last, lightest piece.

**Per-package brief contains:** scope, the contract slice it implements, mocked
dependencies, definition of done (incl. a test against the contract), and "do not touch"
boundaries (never modify contracts — flag instead).

---

## 11. Settled Decisions (quick reference)

- 4 verticals behind a unified Booking/folio; polymorphic Product; per-vertical strategies.
- Opaque immutable `shopperReference`; reconciliation on `merchantReference`; PSP mints
  `pspReference` / `paymentLinkId`.
- One booking → many payments; partial payments; refunds as payment children with
  `originalReference` chain.
- Split vs direct capture per payment (vertical default); **partial capture in**;
  **multi-capture / incremental-auth out**.
- Ledger posts on **capture**, not auth.
- Confirm-then-commit + write-time revalidation + server-side commit gate; **no holds/drafts**.
- `ops-web` is a complete product; MCP is strictly additive; no agent-only endpoints.
- Stack: `core-api` + `payments-sim` (Spring Boot), `ops-web` + `pay-web` (React),
  Postgres on Docker, docker-compose.

> ⚠️ **The `pay-web` mention and the single-Postgres framing in the stack line above are superseded in part by [RX-001](refactor-x/RX-001-psp-direction-and-statefulness.md)** (see Freeze Ledger `WAVE0_00 §1b`). Original text preserved; do not build against the superseded portion without checking the ledger.

---

## 12. Open / Next

- **Next action:** write frozen **Wave 0** artifacts — DDL + enums (sign-off), then the
  OpenAPI spec on top, then the webhook/PSP event contract.
- Nothing currently reopened; all major design questions are settled above.
