# Wave 0 — Foundation & Frozen Contracts (Overview)

> **Purpose of Wave 0.** Produce the frozen seams that let Wave 1 agents build in
> parallel without coordinating with each other. Everything here is a *contract*.
> Once signed off, contracts are **frozen** — changed only via the protocol below.

This overview is the entry point. Read it first, then open the artifact for the seam
you implement.

---

## 1. The artifact set

| File | What it freezes | Primary consumers |
|------|-----------------|-------------------|
| `WAVE0_00_OVERVIEW.md` (this) | How to use the set; freeze rule; change protocol | Everyone |
| `WAVE0_01_SCHEMA.sql` | Postgres DDL — all tables, types, constraints, indexes; enum/glossary | `core-api` (domain, finance, payment), integration owner |
| `WAVE0_02_OPENAPI.yaml` | `core-api` HTTP contract — every read/write endpoint, DTOs, error envelope | `ops-web`, MCP (Wave 2), `core-api` controllers |
| `WAVE0_03_WEBHOOK_PSP_CONTRACT.md` | `payments-sim` event vocabulary, payloads, idempotency; `core-api` consumer rules | `payments-sim`, `pay-web`, `core-api` payment orchestration |
| `WAVE0_04_SCAFFOLD.md` | Repo layout, docker-compose, health checks, run instructions | Integration owner; every package at setup |

> ⚠️ **The `pay-web` mention in the `WAVE0_03` consumer cell above is superseded in part by [RX-001](refactor-x/RX-001-psp-direction-and-statefulness.md)** (see Freeze Ledger §1b). Original text preserved; do not build against the superseded portion without checking the ledger.

Glossary/enums live **inside** `WAVE0_01_SCHEMA.sql` (as the single source of truth for
allowed values) and are referenced — never re-defined — by the other files.

---

## 1a. Requirement IDs (the spine of accountability)

Every contract artifact assigns a **stable ID** to each requirement (table, endpoint,
rule, enum, scaffold step). IDs never change once frozen; if a requirement is retired, its
ID is marked `RETIRED` and never reused. Briefs, commits, PRs, and tests **must reference
these IDs**, so any unit of work traces back to a frozen requirement.

| Prefix | Domain | Lives in |
|--------|--------|----------|
| `ENM-` | Enums / glossary / controlled vocabularies | `01_SCHEMA.sql` |
| `SCH-` | Tables, columns, constraints, indexes, invariants | `01_SCHEMA.sql` |
| `API-` | Endpoints, DTOs, error envelope, auth gate | `02_OPENAPI.yaml` |
| `WHK-` | PSP events, payloads, idempotency, consumer rules | `03_WEBHOOK_PSP_CONTRACT.md` |
| `SCF-` | Repo layout, compose, health checks, run steps | `04_SCAFFOLD.md` |

Cross-references are explicit: an `API-` DTO that maps to a table cites the `SCH-` ID; a
`WHK-` rule that updates a payment cites the `SCH-` and `API-` IDs it touches.

### Accountability surfaces (present in every artifact)
- **Requirements table** — `ID | requirement | acceptance criteria | depends-on`.
- **Accountability block** — owner, status (`DRAFT → FROZEN → IN-BUILD → DONE`), sign-off.
- **Verification log** — the implementing agent records, per ID: what was built, the
  commit/PR reference, and the test that proves it. Empty until Wave 1; this is the
  surface on which agents are held accountable.
- **Changelog** — every change to the artifact, per the protocol in §4.

---

## 1b. Freeze Ledger (THE single source of truth for status)

**This table is authoritative.** Every artifact's own status line, and every "status"
mention in `CLAUDE.md`, defers to this. If an artifact's header disagrees with this table,
this table wins and the header is the bug. Do not record freeze state anywhere else — in
particular, the changelog's first column is a **version number**, never a status.

A row is `FROZEN` only when (a) its content is reviewed and signed off, **and** (b) the
freeze is recorded here with the commit that did it. "Built-against in practice" is noted
where shipped code already depends on the artifact (which is itself why it is frozen).

| Artifact | Slice / IDs | Status | Frozen-at | Superseded-by | Evidence / notes |
|----------|-------------|--------|-----------|---------------|------------------|
| `WAVE0_01_SCHEMA.sql` | SCH-/ENM-/INV- (all) | **FROZEN** | pre-existing | — | `V1__wave0_schema.sql` applied; SCH-001..071 referenced in entities; entity tests pass. Change only via a new Flyway migration. |
| `WAVE0_02_OPENAPI.yaml` | Stage 1 · API-001..007 | **FROZEN** | PR #6 (`ae63af2`) | — | Controllers + DTOs shipped; `BookingFlowApiTest` asserts the contract. |
| `WAVE0_02_OPENAPI.yaml` | API-004 widening (Slice A3) | **FROZEN** | Slice A3 | — | API-004 re-frozen: ROOM-only guard removed; endpoint resolves any registered vertical via `VerticalStrategyRegistry`. Three "ROOM only" text locations updated in contract. Proven by `SpaAvailabilityApiTest` (Testcontainers). |
| `WAVE0_02_OPENAPI.yaml` | Stage 2 · API-008..013 | **FROZEN** | (prior commit) | — | Payment paths + webhook receiver. Implemented in Feature 1 (right half). |
| `WAVE0_03_WEBHOOK_PSP_CONTRACT.md` | WHK-001..015 | **FROZEN** | (prior commit) | [RX-001](refactor-x/RX-001-psp-direction-and-statefulness.md) (pay-web framing only — WHK substance unchanged) | Pairs with API-008..013. Implemented in Feature 1 (right half). |
| `WAVE0_04_SCAFFOLD.md` | SCF-001, SCF-002, SCF-004 | **FROZEN** | pre-existing | — | Actuator/health/compose shipped and running. |
| `WAVE0_04_SCAFFOLD.md` | SCF-003 | **FROZEN** | pre-existing | [RX-001](refactor-x/RX-001-psp-direction-and-statefulness.md) (forward spec only — verification record stands) | Single-Postgres compose shipped & green. Forward spec replaced by SCF-005; SCF-003's §6 verification log is not edited. |
| RX-001 (`refactor-x/RX-001-psp-direction-and-statefulness.md`) | D1/D2/D3 + SCF-005 | **FROZEN** | (this commit) | — | Records `pay-web`-deferred, `payments-sim`-stateful with own Postgres, fail-loud / no-retry outbound + tx-ordering. Append-only; revise only via RX-002. |
| `WAVE0_04_SCAFFOLD.md` (via RX-001) | SCF-005 | **FROZEN** | (this commit) | — | Defined in RX-001 §4; compose/health detail in `WAVE0_05 §7` (drafting). Implementation pending Feature 2. |
| `WAVE0_05_PSP_API.md` | PSP-001..017 | **FROZEN** · IN-BUILD | (this commit) | — | Outbound PSP API + `payments-sim` internal schema + checkout-sim trigger + WHK-015 concrete sync seam + SCF-005 compose detail. Callback path corrected to `/webhooks/psp` (matches shipped `WebhookController`, API-013) at freeze. **Feature 2 Part A (1C-a) DONE: PSP-011/013/015/016 (+PSP-005 mint-on-event, PSP-008 reaffirmed).** **Part B (1C-b) DONE: PSP-001..004 outbound client, PSP-006 tx-ordering, PSP-007 fail-loud 502, PSP-017 four-service compose + live money-loop smoke — proofs in `WAVE0_05 §9a`. PSP-001..017 all DONE.** (Freeze unchanged; per-ID DONE lives in §9a.) |
| `WAVE0_03_WEBHOOK_PSP_CONTRACT.md` | WHK-016 | **DRAFT** | (this slice) | — | Scoped payment→line allocation, additive over WHK-012 (fill-by-line-order retained as the no-coverage default). New `payment_line` table (`V3`, additive). Drafted in `WAVE0_03 §5.1`; awaiting Desk sign-off to freeze. Implementation + proofs are Stage 4 Slice 1. |
| `WAVE0_AUDIT.md` | — | RECORD (not a contract) | `ab83274` | — | Rationale for the Stage 2 contracts + the GAP-1/2 fixes; never "freezes". |

> **FROZEN ≠ DONE.** An artifact being **FROZEN** (its contract is fixed) is separate from
> its IDs being **DONE** (implemented + tested). Stage 2 here is FROZEN-but-not-built: the
> contract is locked, the code is the next step, and GAP-1/GAP-2 (see audit) are fixed *as
> part of* that build. Per-artifact verification logs track DONE per ID; this ledger tracks
> freeze only.

---

## 1c. Reality note — Stage 1 already shipped

The Wave 0 gate in §5 reads as if all parallel work waits for the full set. In practice the
**Stage 1 vertical slice** (customer + room booking, no money) was built and merged against
the frozen `01`/`02-Stage1`/`04` artifacts before the payment contracts existed — consistent
with the freeze rule for those artifacts. The lapse not to repeat: payment/ledger
*schema-backed code* was also added early, ahead of any `WHK-` contract (see `WAVE0_AUDIT.md`).
The §1b ledger + the `CLAUDE.md` gate exist so no further code precedes a `FROZEN` row here.

---

## 2. The mental model an implementing agent must hold

- **`core-api` is the entire system.** All logic, all rules, all state. `ops-web` and the
  future MCP are two thin heads on this one body.
- **Every endpoint must fully serve `ops-web` as a standalone console.** The MCP is a thin
  additive wrapper over the same surface. **No capability may exist solely for the agent.**
  If a brief tempts you to add an "agent-only" endpoint, that is a smell — flag it.
- **DTOs at every boundary.** Entities are never serialised directly. The OpenAPI DTOs are
  the contract; the DDL is internal to `core-api`.
- **Writes revalidate at write time.** Every write re-checks availability and price
  atomically; if state moved since the caller last read, it fails loudly (`409`) with
  current state rather than writing stale data. This is the core safety mechanism.
- **Repercussive writes are human-gated server-side.** `core-api` requires a
  human-authorisation signal the caller cannot self-mint. (Mechanism detailed in
  `02_OPENAPI`.) Confirmation is dual-channel: an `ops-web` click and an agent "yes" are
  equally valid.
- **Ledger posts on capture, not auth.** Authorisation is a hold, not a financial event.

---

## 3. The freeze rule

After sign-off, the five artifacts are **frozen**. While frozen:

- **No agent edits a contract.** Not the SQL, not the YAML, not the webhook payloads.
- Agents build their package *against* the contracts, mocking everything they don't own.
- "Done" for a package means: it implements its contract slice **and** has a test that
  asserts against the contract (schema shape / endpoint shape / webhook payload shape).

The freeze is what makes parallelism safe. An agent that silently "improves" a DTO breaks
every other agent depending on it, days later, invisibly.

---

## 4. The contract-change protocol

Contracts will need changes — that's expected. The discipline is *how*:

1. **Flag, don't fix.** The agent that hits a problem files a flagged question to the
   central arbiter (you). It describes the problem and the proposed change. It does **not**
   edit the contract or route around it locally.
2. **Arbitrate centrally.** The arbiter decides, edits the contract **once**, bumps a
   version note at the top of the affected file, and records the change in that file's
   changelog section.
3. **Propagate.** The arbiter notifies every package whose brief references the changed
   slice. Affected agents re-sync to the new contract.

One rule prevents ~90% of parallel-agent chaos: **never modify a contract to unblock
yourself; flag it.**

---

## 5. Definition of done for Wave 0 itself (the gate)

Wave 0 is complete — and Wave 1 may begin — only when **all** of:

- [ ] `01_SCHEMA.sql` applies cleanly to a fresh Postgres and is signed off.
- [ ] `02_OPENAPI.yaml` validates and covers every read + write in the capability surface.
- [ ] `03_WEBHOOK_PSP_CONTRACT.md` fully specifies every PSP event + the consumer rules.
- [ ] `04_SCAFFOLD.md` brings up all services via docker-compose with green health checks
      and an empty-but-wired skeleton (no business logic yet).
- [ ] Enums/glossary in `01` are the sole source of allowed values; nothing contradicts it.

Until every box is ticked, **nothing parallel starts.** This is the gate.

---

## 6. Wave 1 packages (for context; briefs come after the gate)

- **A.** Core domain + persistence (publishes domain interfaces day one)
- **B.** Vertical strategies (Rooms / Spa / F&B / Events) — availability, pricing, capture default
- **C.** Ledger / finance (consumes booking events via outbox)
- **D.** Payment orchestration in `core-api` (links, webhooks, idempotency)
- **E.** `payments-sim` + `pay-web`
- **F.** `ops-web` — a complete console, built against the OpenAPI mock

> ⚠️ **Package E above is superseded in part by [RX-001](refactor-x/RX-001-psp-direction-and-statefulness.md)** (see Freeze Ledger §1b). Original text preserved; do not build against the superseded portion without checking the ledger.

One **integration owner** holds `docker-compose` + the end-to-end smoke test throughout.

---

## 7. Changelog

| Version | Date | Change |
|---------|------|--------|
| 0.1 | (draft) | Initial Wave 0 set drafted for sign-off. |
| 0.2 | (prior commit) | Added §1b Freeze Ledger (single source of truth) + §1c reality note. Recorded 01 / 02-Stage1 / 04 FROZEN (built-against); froze 02-Stage2 + 03. |
| 0.3 | 2026-06-12 | §1b: added **Superseded-by** column; populated with RX-001 on the affected rows (`pay-web` framing in `WAVE0_03`; SCF-003's forward spec). Added rows for RX-001 (FROZEN) and SCF-005 (FROZEN, born in RX-001). Registered `WAVE0_05_PSP_API.md` as **DRAFT**. Added pointer banners (no edits to requirement text or verification logs) in §1 consumer cell and §6 Wave 1 packages. |
| 0.4 | 2026-06-12 | Froze `WAVE0_05_PSP_API.md` (PSP-001..017): §1b row DRAFT→FROZEN, ID range pinned. Corrected the outbound callback path `/v1/payments/webhooks` → `/webhooks/psp` (3 refs in WAVE0_05 §1/§2/§7.2) to match the shipped `WebhookController` (API-013) before freeze. No requirement semantics changed; path correction only. |
| 0.5 | 2026-06-13 | §1b `WAVE0_05` row: noted **IN-BUILD** — Feature 2 Part A (1C-a) DONE (PSP-011/013/015/016, +PSP-005 mint-on-event, +PSP-008 reaffirmed); per-ID proofs recorded in `WAVE0_05 §9a`. Freeze status unchanged (still FROZEN at v0.4); this row records build progress only, per the freeze-vs-done split. |
| 0.6 | 2026-06-13 | §1b `WAVE0_05` row: Feature 2 Part B (1C-b) DONE — PSP-001..004 outbound client (`PspGateway`), PSP-006 tx-ordering (`PaymentOrchestrator`; class-level `@Transactional` removed from `PaymentService`), PSP-007 fail-loud 502, and PSP-017 (four-service `docker compose` up healthy + live money-loop smoke via `docker-compose.smoke.yml`). PSP-001..017 all DONE; per-ID proofs in `WAVE0_05 §9a`. Freeze unchanged. |
| 0.7 | 2026-06-13 | §1b: added API-004 widening row (Slice A3). API-004 re-frozen to vertical-agnostic; three "ROOM only" text locations updated in `WAVE0_02_OPENAPI.yaml`. `SpaAvailabilityApiTest` proves SPA availability over HTTP end-to-end (Testcontainers). Gate A closed. |
| 0.8 | 2026-06-14 | §1b: added **WHK-016 (DRAFT)** row — scoped payment→line allocation (Stage 4 Slice 1), additive over WHK-012; new `payment_line` table via additive `V3` migration. Drafted in `WAVE0_03 §5.1`. `WAVE0_02` API-008 **request** DTO unchanged (scoping wired at the service layer; HTTP exposure deferred to a separate API-contract change). One **additive, backward-compatible** read field — `FolioResponse.amountAuthorised` (D3 live "secured" roll-up, visible-only) — added to `WAVE0_02` FolioResponse (API-005/006/007) + additive `booking.amount_authorised` column in `V3`. WHK-016 not frozen — awaiting Desk sign-off. |
