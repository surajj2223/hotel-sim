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

One **integration owner** holds `docker-compose` + the end-to-end smoke test throughout.

---

## 7. Changelog

| Version | Date | Change |
|---------|------|--------|
| 0.1 | (draft) | Initial Wave 0 set drafted for sign-off. |
