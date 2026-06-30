# HS-08 — Delivery Plan & Waves

| Field            | Value                                                        |
|------------------|--------------------------------------------------------------|
| **ID**           | HS-08                                                        |
| **Title**        | Delivery Plan & Waves                                        |
| **Version**      | 0.3                                                          |
| **Contract Status** | Frozen                                                    |
| **Build Status** | In Progress — Wave 1 @ Stage 3.1 (tagged `stage-3.1`); Stages 1–3 closed; see Stage map below |
| **Date**         | 2026-06-30                                                   |
| **Owner**        | Integration Owner                                            |
| **Freezes**      | Wave 0 / 1 / 2 structure · Tracks A–F · Integration owner role · Gate conditions |

---

## Overview

Delivery is structured in three waves. The principle is **front-load the contracts** so
parallel agents and developers can code against frozen seams, not each other. No agent
owns a contract; contract changes are arbitrated centrally and propagated.

---

## Wave 0 — Foundation (Sequential · FREEZE before proceeding)

**Purpose:** Produce the frozen seams that let Wave 1 build in parallel without
coordinating with each other.

**Artifacts:**

| Artifact | What it freezes | Status |
|----------|-----------------|--------|
| `WAVE0_00_OVERVIEW.md` | How to use the set; freeze rule; change protocol | Frozen |
| `WAVE0_01_SCHEMA.sql` | Postgres DDL — all tables, types, constraints, indexes; enum/glossary | Frozen |
| `WAVE0_02_OPENAPI.yaml` | `core-api` HTTP contract — every read/write endpoint, DTOs, error envelope | Frozen |
| `WAVE0_03_WEBHOOK_PSP_CONTRACT.md` | `payments-sim` event vocabulary, payloads, idempotency; `core-api` consumer rules | Frozen |
| `WAVE0_04_SCAFFOLD.md` | Repo layout, docker-compose, health checks, run instructions | Frozen |

**Gate conditions (all must be met before Wave 1 starts):**
- `WAVE0_01_SCHEMA.sql` applies cleanly to a fresh Postgres and is signed off.
- `WAVE0_02_OPENAPI.yaml` validates and covers every read + write in the capability surface.
- `WAVE0_03_WEBHOOK_PSP_CONTRACT.md` fully specifies every PSP event + consumer rules.
- `WAVE0_04_SCAFFOLD.md` brings up all services via docker-compose with green health checks.
- Enums/glossary in `WAVE0_01` are the sole source of allowed values.

---

## Wave 1 — Parallel Build (Against frozen contracts, with mocks)

Six tracks run in parallel once Wave 0 is frozen. Track A publishes domain interfaces on
day one; B–F then run flat out against those interfaces and the frozen Wave 0 contracts.

| Track | Scope | Key contracts consumed |
|-------|-------|------------------------|
| **A** | Core domain + persistence. Publishes domain interfaces early. | `WAVE0_01_SCHEMA.sql`, `WAVE0_02_OPENAPI.yaml` |
| **B** | Vertical strategies: Rooms, Spa, F&B, Events. | Track A domain interfaces, HS-02 |
| **C** | Ledger / finance. Consumes booking events via outbox. | Track A domain interfaces, HS-03 |
| **D** | Payment orchestration in `core-api` (links, webhooks, idempotency). | `WAVE0_02_OPENAPI.yaml`, `WAVE0_03_WEBHOOK_PSP_CONTRACT.md`, HS-04 |
| **E** | `payments-sim` + `pay-web`. | `WAVE0_03_WEBHOOK_PSP_CONTRACT.md`, HS-04 |
| **F** | `ops-web` — complete console, built against the OpenAPI mock. | `WAVE0_02_OPENAPI.yaml`, HS-05 |

**Integration owner** holds `docker-compose` and the end-to-end smoke test throughout Wave 1.

> **Plan-of-record vs. actual execution.** The Tracks A–F table above is the *original
> plan* and is retained as such. In practice, execution was sequenced as a linear Stage
> march (Stages 1..8) rather than flat-parallel tracks. The Stage reality is below.

---

## Wave 1 delivery — Stage reality

Wave 1 (build the body) is delivered as a linear Stage march, not flat-parallel Tracks A–F.
The single authoritative status source remains the Freeze Ledger (`WAVE0_00_OVERVIEW.md
§1b`); this section is a reading aid, not a competing status.

| Stage | Scope | Status |
|-------|-------|--------|
| **Stage 1** | Book a room (no payments). | ✅ Merged. |
| **Stage 2** | Get paid: payment links, async webhook-driven capture/cancel/refund, ledger posts on capture, scoped cross-vertical allocation (WHK-016), SPA vertical + availability. | ✅ Closed, tagged `stage-2` / `stage-2.1`. |
| **Stage 3** | Third vertical + lifecycle + reads: F&B vertical (strategy, availability `fnbAttributes`, booking), folio completion lifecycle (`completeLine`/`completeFolio`, API-014/015), and the charter §9 reporting reads (`getRevenue` API-016, `listUnpaidBookings` API-017). | ✅ Closed, tagged `stage-3` / `stage-3.1`. |
| **Stages 4–8** | Remaining body work — cross-vertical depth, further operational reads, the EVENT vertical. | ⏳ Not yet built. |

**Wave 2 (MCP)** is unchanged and still future.

---

## Wave 2 — Integration & MCP

1. **Full smoke test:** create customer → search → cross-vertical folio → confirm
   (in ops-web tray or agent chat) → pay → see postings.
2. **MCP server:** built as the last, lightest piece once smoke test is green.
   Thin tool-wrapper over `core-api` endpoints. See HS-07.

---

## Contract-change protocol (applies to all waves)

1. **Flag, don't fix.** The agent/developer that hits a problem files a flagged
   question to the central arbiter — describing the problem and the proposed change.
   It does **not** edit the contract or route around it locally.
2. **Arbitrate centrally.** The arbiter decides, edits the contract once, bumps the
   version note, and records the change in the file's changelog section.
3. **Propagate.** The arbiter notifies every package whose brief references the changed
   slice. Affected agents re-sync.

**One rule prevents ~90% of parallel-agent chaos: never modify a contract to unblock
yourself; flag it.**

---

## Requirement IDs

Every contract artifact uses stable requirement IDs. IDs never change once frozen; if
retired, marked `RETIRED` and never reused. Briefs, commits, PRs, and tests reference these IDs.

| Prefix | Domain |
|--------|--------|
| `ENM-` | Enums / glossary / controlled vocabularies |
| `SCH-` | Tables, columns, constraints, indexes, invariants |
| `API-` | Endpoints, DTOs, error envelope, auth gate |
| `WHK-` | PSP events, payloads, idempotency, consumer rules |
| `SCF-` | Repo layout, compose, health checks, run steps |

---

## Contract-Drift Log

> Frozen contracts may not drift silently. Any deviation a builder must make is recorded
> here with date, the clause affected, what changed, and why. Until a row is arbitrated
> and the contract re-frozen, **the code is the source of truth for that clause.**
> This is the hotel-sim analogue of NikkFit's "Code-vs-PRD Errata," but it governs a
> *frozen contract*, so it doubles as the central-arbitration record.

| Date | Clause | Change | Reason | Arbitrated |
|------|--------|--------|--------|------------|
| 2026-06-17 | HS-08 Wave 0 table — `WAVE0_03` status | Deferred → Frozen | Doc was stale; `WAVE0_03` is FROZEN and fully implemented (the money loop) per `WAVE0_00 §1b`. Docs conform to the ledger, not vice-versa. | Yes |
| 2026-06-30 | HS-08 Build Status + Stage map | Stage 2 → Stage 3.1 closed | Doc was stale at the `stage-2` era. Since then F&B (vertical + availability `fnbAttributes` + booking), folio completion (API-014/015), and the §9 reports (API-016/017) all shipped (tags `stage-3` / `stage-3.1`) per `WAVE0_00 §1b`. Prose/status only. | Yes |
