# Copilot Instructions — Hospitality Operations Platform (POC)

These are the house rules for any agent writing code in this repo. They are durable
conventions, not a spec. The spec lives elsewhere — see "Source of truth" below. When this
file and a frozen contract disagree, **the contract wins**; flag the conflict (see §8).

---

## 1. Source of truth (read before coding)

- `project-brief.md` (repo root) — the **why**: the thesis, domain, settled decisions.
- `contracts/WAVE0_00_OVERVIEW.md` — how the contract set works, the freeze rule, the
  requirement-ID scheme, the change protocol.
- `contracts/WAVE0_01_SCHEMA.sql` — the database contract (enums `ENM-*`, schema `SCH-*`,
  invariants `INV-*`).
- `contracts/WAVE0_02_OPENAPI.yaml` — the HTTP contract (`API-*`).
- `contracts/WAVE0_03_WEBHOOK_PSP_CONTRACT.md` — the PSP/webhook contract (`WHK-*`).
- `contracts/WAVE0_04_SCAFFOLD.md` — repo layout, compose, health checks (`SCF-*`).
- Your **package brief** in `contracts/briefs/` — your scope, your contract slice, your
  mocked dependencies, your definition of done, your do-not-touch boundaries.

**Stack-specific structure & conventions** (read the one for the codebase you're in):
- `.github/instructions/core-api.instructions.md` — Spring Boot services (`core-api`,
  `payments-sim`): directory tree, package-by-feature layout, layering rules, naming.
- `.github/instructions/web.instructions.md` — React apps (`ops-web`, `pay-web`): folder
  structure, component/hook/API-client patterns, generated OpenAPI client.

Do not restate or re-derive these. Reference them by requirement ID.

---

## 2. The one rule that protects everyone: contracts are frozen

- **Never edit a frozen contract to unblock yourself.** Not the SQL, not the YAML, not the
  webhook payloads. If a contract seems wrong or insufficient, **flag it** (§8) — do not
  edit it, and do not route around it with a local hack.
- Build against the contract, mocking anything you don't own.
- Stay inside your package's "do not touch" boundaries.

---

## 3. Architecture & layering

- **`core-api` holds all logic, rules, and state.** Web/clients are thin. Never put
  business rules in `ops-web`, `pay-web`, or (later) the MCP layer.
- Layering is hexagonal-ish: `controller -> application service -> domain -> repository`.
  Dependencies point inward. Controllers are thin; domain has no framework leakage.
- **No capability exists solely for the agent.** Every endpoint must fully serve `ops-web`
  as a standalone console. If you're tempted to add an agent-only endpoint, stop — flag it.
- Use the **Strategy pattern per vertical** (Rooms/Spa/F&B/Events) for availability,
  pricing, and `defaultCaptureMode()`. Vertical-specific behaviour lives there, in one
  place — not in `if (vertical == ...)` branches scattered across services.

---

## 4. DTOs, money, and data discipline

- **DTOs at every boundary. Never serialise JPA entities.** Request/response shapes come
  from the OpenAPI contract; map entity <-> DTO explicitly.
- **Money is always integer minor units (`BIGINT` / `long`), plus a currency code.**
  Never `float`/`double` for money. Never do money math in floating point. A price of
  £200.00 is `20000`.
- **"Paid" is `balance == 0`, never a boolean.** Balance = `total - paid + refunded`
  (see `SCH-021`). Amount roll-ups are maintained by `core-api` on capture/refund events
  (`INV-004`), never by clients.
- Respect the reference taxonomy exactly: `shopperReference` (ours, opaque, on customer),
  `merchantReference` (ours, per payment attempt, reconciliation anchor), `pspReference`
  and `paymentLinkId` (minted by `payments-sim`), `originalReference` (child→parent chain).
  We mint the first two; we only ever store the PSP's.

---

## 5. Writes, safety, and the human gate

- **Every write revalidates at write time** (`INV-003`): re-check availability and price
  atomically; if state moved since the caller's last read, **fail loudly with `409` and
  current state** — never write stale data.
- **Repercussive writes are human-gated server-side** (`INV-007`): require the
  human-authorisation signal defined in the OpenAPI contract; the caller must not be able
  to self-mint it. Confirmation is dual-channel — an `ops-web` click and an agent "yes" are
  equally valid; the gate logic is identical for both.
- **Ledger posts on capture, not auth** (`INV-006`). Authorisation/cancellation produce no
  posting. Capture → `REVENUE`; refund → `REFUND_REVERSAL`.
- **Webhooks are idempotent** (`SCH-070/071`): match inbound to a payment by
  `merchantReference`, stamp the returned `pspReference`, dedupe by the idempotency key.
- **Single capture per auth** (`INV-005`); partial capture is allowed, multi-capture and
  incremental-auth are out of scope — reject them.

---

## 6. Testing & accountability

- Every requirement you implement gets a test that **references its ID** (e.g. a test named
  or tagged `SCH-032`, `API-031`, `WHK-007`).
- After implementing a requirement, **fill the verification log** in the relevant contract
  file: requirement ID, what you built, the commit/PR, the proving test. This is how work
  is tracked and audited — an unlogged requirement is treated as not done.
- Tests for the service-layer invariants (`INV-001`..`INV-007`) are mandatory, not optional.
- Prefer fast, deterministic tests; use the contract (schema/OpenAPI/webhook) as the oracle.

---

## 7. Style & scope (stay POC)

- Match the stack: Spring Boot (`core-api`, `payments-sim`), React (`ops-web`, `pay-web`),
  Postgres, docker-compose. Don't introduce new frameworks without a flag.
- **Deliberately skip** (it's a POC): microservice-per-vertical, event bus/Kafka, CQRS, real
  auth (stub only), real money, multi-currency, scaling concerns, holds/drafts. Don't build
  these even if they'd be "more correct" — they're explicitly out of scope.
- Keep functions and classes small and named for the domain. Comments explain *why*, not
  *what*. No dead code, no speculative abstraction.
- Follow existing patterns in the package before inventing new ones.

---

## 8. When something is wrong: flag, don't fix

If a contract is wrong, ambiguous, or blocks you:

1. **Stop. Do not edit the contract and do not hack around it.**
2. Open an issue / leave a clearly-marked `FLAG:` note describing the problem, the
   requirement ID involved, and your proposed change.
3. Continue on unblocked work; let the central arbiter decide. The arbiter edits the
   contract once, bumps its version, and propagates. Then you re-sync.

This single rule prevents most parallel-agent breakage. A contract changed quietly by one
agent silently breaks every other agent depending on it.

---

## 9. Commit hygiene

- Reference requirement IDs in commit messages where applicable
  (e.g. `feat(core-api): implement payment capture [SCH-032, API-031]`).
- Small, focused commits over large mixed ones.
- Don't commit secrets, real keys, or `.env` files. This system uses stubbed auth and a
  fake PSP — there are no real credentials, and there shouldn't be.
