# HS-06 — Human-in-the-Loop & Safety

| Field            | Value                                                        |
|------------------|--------------------------------------------------------------|
| **ID**           | HS-06                                                        |
| **Title**        | Human-in-the-Loop & Safety                                   |
| **Version**      | 0.1                                                          |
| **Contract Status** | Draft                                                     |
| **Build Status** | Not Started                                                  |
| **Date**         | 2026-06-10                                                   |
| **Owner**        | Track A (commit gate in core-api) + Track F (ops-web confirmation UX) |
| **Freezes**      | Confirm-then-commit · Write-time revalidation · Server-side commit gate · Dual-channel confirmation |

---

## Overview

Writes are never autonomous. The MCP performs writes, but a human confirms before
anything with repercussions. This PRD defines the four HITL mechanisms that make this
safe — and why they are designed as they are.

---

## The Four Mechanisms

### 1. Confirm-then-commit

**What:** The agent reads availability, proposes in chat, the human confirms, then
the agent calls one write endpoint.

**Why this design:** No draft/two-phase ceremony. The agent never holds a provisional
state that must be cleaned up on timeout. The availability window between read and
confirm is acceptable because write-time revalidation catches any movement.

**Flow:**
```
Agent reads availability  →  proposes in chat
Human says "yes"          →  agent calls POST /bookings (single call)
core-api validates        →  writes or rejects
```

### 2. Write-time revalidation

**What:** Every write atomically re-checks availability and price. If state has moved
since the agent last read, the request fails loudly with the current state rather than
writing stale data.

**Why this design:** This is the core safety mechanism. It is cheaper and more reliable
than distributed locks or draft reservations. The caller (agent or UI) receives a `409
Conflict` response with the current state, enabling an informed retry.

**Applies to:** all writes in the capability surface (HS-05).

### 3. Server-side commit gate

**What:** `core-api` requires a human-authorisation signal on repercussive writes. The
agent cannot self-mint this signal.

**Why this design:** Safety lives in the server, not in the MCP behaving well. An agent
that misbehaves (hallucinating a "yes" or looping) cannot write without the signal.
The signal must come from an external human action.

**Signal sources (dual-channel — see mechanism 4):**
- An `ops-web` button click (carries the signal as a UI-generated token or flag).
- An agent "yes" in conversation (the human's message is the authorisation; the agent
  forwards it, but does not fabricate it).

### 4. Dual-channel confirmation

**What:** The same write can be authorised by a click in `ops-web` OR a "yes" in the
agent conversation. Both are legitimate human authorisation.

**Why this design:** Demonstrating that the same `createBooking` called from a button and
from the agent lands identically in the ledger is itself a slice of the proof. The two
channels converge at the same endpoint with the same rules applied.

---

## What is explicitly deferred

- **Soft holds / drafts** (TTL'd inventory reservation). Not built in the POC.
  The availability seam is kept clean so holds are purely additive later — a hold becomes
  "another thing that consumes availability."
- Approval workflows with multiple approvers.
- Audit log beyond the existing outbox/event log.

---

## Safety invariants (non-negotiable)

1. No write may succeed without passing write-time revalidation.
2. No repercussive write may succeed without a human-authorisation signal.
3. The agent cannot self-mint the human-authorisation signal.
4. `ops-web` click and agent "yes" produce identical ledger outcomes.
5. A failed write must return the current state — enabling informed retry.

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
