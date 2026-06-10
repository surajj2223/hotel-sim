# CLAUDE.md — hotel-sim (root)

Operations-platform POC. A complete *headful* system (`core-api` + `ops-web`) that an
additive MCP layer later makes headless. `core-api` is the whole body; everything else is a
head or a supporting service. Full charter: @contracts/project-brief.md

> This file is intentionally thin. Heavy reference is imported on demand, not pasted here.
> Read the imports when the task touches their area; do not work from memory.

## The one rule that prevents the most damage

**No implementation code before its contract is FROZEN.** Every payment, ledger, webhook,
or endpoint behaviour must trace to a requirement ID (`SCH-`/`ENM-`/`API-`/`WHK-`/`SCF-`)
that already exists in a **FROZEN** contract artifact. If the code you are about to write
implements a decision that is **not** written in a contract — STOP and flag it. Do **not**
infer the decision from the schema or the charter and proceed.

This rule exists because it was once absent: payment/ledger code was written ahead of its
contract and shipped three silently-wrong gaps (see @contracts/WAVE0_AUDIT.md). The schema
compiled, the tests passed, and revenue was still attributed to the wrong vertical. A green
build does not mean the design decision was correct — only a frozen, reviewed contract does.

## Contracts are frozen seams — flag, never fix

- Frozen artifacts live in `contracts/`. **Never edit a frozen contract to unblock
  yourself.** File a flagged question to the arbiter instead (the protocol is in
  @contracts/WAVE0_00_OVERVIEW.md §4).
- "Flag don't fix" applies to apparent conflicts too. If a contract looks wrong, say so;
  do not route around it locally.
- Every commit message cites the requirement ID(s) it implements (e.g.
  `feat(core-api): per-line REVENUE postings [WHK-007, WHK-012]`).

## How to work (plan-then-build, gated)

1. **Read before writing.** Inspect the actual files — entities, services, repositories,
   build files, the relevant FROZEN contract — before drafting code. Ground in real repo
   state, not charter aspirations.
2. **Plan, then wait.** For any non-trivial change, present the plan and stop at the gate.
   Do not implement past the gate without explicit go-ahead.
3. **One stage / one part at a time.** Scope strictly. Do not pull later-stage work forward.
4. **Done = implements the contract slice AND has a test asserting against the contract.**

## Hard architectural invariants (do not violate; do not "improve" away)

- **Money is `BIGINT` minor units + 3-letter currency. Never floats. Anywhere.**
- **Ledger posts on CAPTURE, never on AUTHORISATION** (INV-006). Auth is a hold.
- **Single capture per auth** (INV-005). Multi-capture / incremental-auth are out of scope.
- **Per-line ledger postings.** A capture/refund posts one row per covered booking line,
  each carrying that line's `vertical` (WHK-007/012). Do **not** attribute a whole capture
  to one vertical — that is GAP-1, the bug this codebase already had.
- **Repercussive writes are human-gated server-side** via `X-Human-Auth` → `428` if absent
  (INV-007). The gate is enforced in the controller, not assumed in the client.
- **Writes revalidate at write time**; stale state fails loudly with `409` (INV-003).
- **DTOs at every boundary.** Entities are never serialised.
- **No capability exists for the agent alone.** Every endpoint must fully serve `ops-web`.

## Named traps (these have bitten this repo or will)

- **Do not add a third overlap-availability query.** Two exist:
  `BookingLineRepository.lockedCountCommitted`, `ProductRepository.countCommittedQuantity`.
- **`VerticalStrategyRegistry` silently fails** to register a strategy that is missing the
  `VerticalStrategyRegistration` marker interface. Always implement the marker.
- **`@Transactional` on a non-public or self-invoked method is a no-op** (Spring proxy).
  This was the root cause of GAP-2 (outbox double-posting). Put `@Transactional` on a
  public method of a separate bean.
- **The `payments-sim` synchronous webhook seam (WHK-015) is test-only.** It must be
  unreachable in the running system — test-profile bean or external harness, never a prod
  code path.

## Map

- `core-api/` — Spring Boot. All logic, domain, persistence, payment orchestration, ledger.
- `payments-sim/` — fake PSP (mints `pspReference`/`paymentLinkId`, fires webhooks).
- `ops-web/` — React operations console (Head 1).  `pay-web/` — hosted checkout sim.
- `contracts/` — frozen seams + the audit. Read the relevant one before touching its area.
- `docs/system-design/` — PRD site (HS-00..HS-08).

## Status (keep current)

- Stage 1 (customer + room booking, no money): **DONE**, HTTP-wired, on `main`.
- Stage 2 (get paid): contracts `WAVE0_03` + `WAVE0_02` Stage 2 slice (`API-008..013`)
  drafted; **freeze pending**. Payment/ledger domain code exists but is unwired and carries
  GAP-1/GAP-2 (see audit) — to be fixed *as part of* the Stage 2 build, not separately.
  `getRevenue` and other reporting reads are deferred to Stage 6; per-line posting
  correctness is proven in Stage 2 by asserting `LedgerPosting` rows directly in tests.
