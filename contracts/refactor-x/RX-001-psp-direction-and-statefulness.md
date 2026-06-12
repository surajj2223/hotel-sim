# RX-001 — PSP outbound direction & `payments-sim` statefulness

> **Refactor record (append-only).** This is not a new contract; it is a delta against
> existing frozen contracts. It records three decisions that turned during Feature 2
> planning, names every frozen statement those decisions supersede, and introduces one new
> requirement (`SCF-005`) born here.
>
> **The Freeze Ledger (`WAVE0_00 §1b`) is the authoritative index.** A reader landing on a
> superseded statement in isolation must consult §1b's `Superseded-by` column before
> building against it. The pointer banners on superseded statements say *only* that — they
> do not restate the change. The "what changed" lives here.
>
> **Self-rule (append-only).** RX-001 is itself frozen on commit. It is revised only by a
> superseding `RX-002`, never edited in place. Same append-only discipline as the
> `WAVE0_0X` contracts.
>
> **Status:** `FROZEN` on commit of this file (authoritative in `WAVE0_00 §1b`).
> **Owner / arbiter:** Desk.
> **Depends on:** `WAVE0_03_WEBHOOK_PSP_CONTRACT.md` (inbound webhook contract, unchanged),
> `WAVE0_04_SCAFFOLD.md` (SCF-001..004), `WAVE0_00_OVERVIEW.md` §1b/§4.

---

## 1. Context

Feature 1 shipped the **right half** of payments inside `core-api`: payment/refund
entities, `PaymentService` request/settle split, the inbound webhook receiver (API-013 /
WHK-001..014), and outbox → ledger postings (WHK-007/009/012/013, GAP-1/GAP-2 closed).

Feature 2 builds the **left half**: a real `payments-sim` service that mints PSP
references, persists its own state, simulates checkout, and emits webhooks; and an
outbound seam from `core-api` to it that orchestrates link creation / capture /
cancellation / refund over real HTTP. The inbound contract (`WAVE0_03`) is unchanged —
`payments-sim` is now the named source of those events.

In planning Feature 2 three earlier assumptions reversed. Two are corrections to
contract-level decisions (a built-out `pay-web`, a single shared Postgres); one is a new
architectural commitment about *how* `core-api` should call the PSP and what to do when
that call fails. Editing the frozen contracts in place would falsify their verification
records — what `WAVE0_04` SCF-003's verification log records is what Stage 1 *actually*
shipped, and that is true and must remain true. Per `WAVE0_00 §4` and the append-only
discipline, the deltas go here.

---

## 2. Decisions

### D1 — Checkout is simulated inside `payments-sim`; `pay-web` is not built in Feature 2.

`payments-sim` exposes a small operator/test-facing trigger ("simulate guest authorises
this link") and may serve one trivial confirmation page itself. The webhook seam is
identical whether a human pays on a future `pay-web` or the trigger fires.

**Rationale.** The POC's thesis is the *integration shape* (real HTTP + real webhooks,
async completion). A built React checkout adds zero proof beyond the trigger but doubles
the surface area Feature 2 has to ship. `pay-web` becomes a later cosmetic upgrade —
purely additive over an unchanged seam.

### D2 — `payments-sim` is stateful, with its own Postgres instance.

`payments-sim` runs against a **second Postgres instance**, separate from `core-api`'s. It
persists a small reference ledger (`merchantReference → pspReference`,
`amountAuthorised` / `amountCaptured` / `amountRefunded`, status), an event sequence
counter per PSP transaction (so `idempotencyKey` per `WHK-003` is deterministic and stable
across redeliveries), and refund reconciliation (mints a *distinct* refund `pspReference`,
rejects refunds exceeding captured).

**Rationale.** A stateless simulator cannot honour WHK-003 (stable `idempotencyKey =
pspReference:eventCode:seq`), cannot enforce its own "refund ≤ captured" guard, and cannot
keep `pspReference` ↔ `merchantReference` consistent across restarts — every one of those
is a property a real PSP provides. Sharing `core-api`'s instance collapses the very
service boundary the POC exists to prove (two services, one network, one webhook hop) into
a hidden in-process coupling. Two databases is the only honest shape.

### D3 — Outbound PSP-call failures are fail-loud / no-retry.

On any failure of a `core-api → payments-sim` call (connection refused, timeout, 5xx,
malformed response): `core-api` surfaces a `502`-class error to the operator; the payment
is left in its **pre-call state**, with no partial writes; **no auto-retry**. Clean state
comes from **transaction ordering**, not from rolling back across a network call:

> *validate-and-persist `PENDING` → commit → call PSP → stamp PSP response in a new
> transaction.*

The PSP HTTP call must never happen inside an open DB transaction (cousin of the GAP-2
proxy trap: a network round-trip inside `@Transactional` holds a Hikari connection across
seconds of waiting, then fails in a way the framework cannot meaningfully roll back).
Outbound idempotency tokens and retry policy are explicitly deferred post-POC, in the same
register as holds/drafts.

**Rationale.** Silent retry of a payment-initiation call is dangerous (double-auth,
double-capture against a real PSP). The honest POC behaviour is to make the failure
visible: the operator sees `502`, the payment row is still `PENDING`, they decide whether
to retry by *re-issuing* the operator action — which lands on a fresh `merchantReference`
and is therefore safe.

---

## 3. Supersedes (ID-level, with quoted source)

For each superseded statement, the original is **preserved verbatim** in its file. A
pointer-only banner is added beside it pointing here and to the Freeze Ledger. **None of
the verification logs in the superseded files are touched.** The Stage 1 verification
records (e.g. SCF-003 in `WAVE0_04 §6`) remain valid for what they record: what Stage 1
shipped. They describe history, not forward spec.

### 3.1 `WAVE0_04_SCAFFOLD.md` — SCF-003 (forward-spec only)

Quoted source (`WAVE0_04 §5`, SCF-003 row):

> *"`docker-compose up` brings up db + core-api, health-gated. | `db` =
> `postgres:16-alpine` with named volume + `pg_isready` healthcheck; `core-api` built from
> its Dockerfile, `depends_on` db `service_healthy`, datasource set via environment,
> `8080:8080`, healthcheck on `/actuator/health`."*

And `WAVE0_04 §3` configuration seam:

> *"DB name / user / password | `hotelops` / `hotelops` / `hotelops` | compose `db` env +
> `application.yml`"* (one Postgres service, one datasource).

**Superseded as a forward spec only.** SCF-003's verification record (`WAVE0_04 §6`)
remains valid for Stage 1: a single `db` + `core-api` compose did ship green and is what
the verification asserts. The replacement forward spec — two Postgres services, two
service-healthy gates, `payments-sim` on its own DB — is **SCF-005** (§4 below), with full
compose/health detail in `WAVE0_05`.

### 3.2 `pay-web`-as-built-service framings

These lines describe `pay-web` as something Feature 2 (Wave 1 Package E) builds. D1
reverses that. Banners are added; the lines remain unedited.

- `contracts/project-brief.md §7` systems table:
  > *"`pay-web`      | React            | Simulated hosted checkout the customer 'pays' on, triggering the webhook."*

- `contracts/project-brief.md §10` Wave-1 package list:
  > *"E. `payments-sim` + `pay-web`"*

- `contracts/project-brief.md §11` stack line:
  > *"Stack: `core-api` + `payments-sim` (Spring Boot), `ops-web` + `pay-web` (React), Postgres on Docker, docker-compose."*

- `contracts/WAVE0_00_OVERVIEW.md §1` artifact table, consumer cell for `WAVE0_03`:
  > *"`payments-sim`, `pay-web`, `core-api` payment orchestration"*

- `contracts/WAVE0_00_OVERVIEW.md §6` Wave 1 packages:
  > *"**E.** `payments-sim` + `pay-web`"*

- `contracts/WAVE0_03_WEBHOOK_PSP_CONTRACT.md §3` envelope note:
  > *"Common envelope on every event (POC JSON; `pay-web`/`payments-sim` produce, `core-api` consumes):"*

- `contracts/WAVE0_03_WEBHOOK_PSP_CONTRACT.md §8` accountability consumers:
  > *"Consumers | `payments-sim`, `pay-web`, `core-api` payment orchestration, integration owner"*

After D1, `pay-web` is a **deferred post-POC cosmetic upgrade**. `payments-sim` produces
the webhook envelopes; an operator/test trigger drives the authorisation that `pay-web`
would otherwise drive (concrete shape: `WAVE0_05 §5`). The webhook envelope itself
(`WAVE0_03 §3`) is unchanged.

`WAVE0_03 §6a` already notes that `core-api`'s webhook receiver is identical whether
authorisation arrives from a real `pay-web`, the trigger, or the test sync seam — that
line is fine and is not superseded.

### 3.3 `Single instance` framing

- `contracts/project-brief.md §7` systems table:
  > *"`db`           | Postgres (Docker)| Single instance."*

After D2, the POC runs **two** Postgres instances: one owned by `core-api`, one owned by
`payments-sim`. They are wired in compose under SCF-005 (§4). They never share a schema,
a connection pool, or a transaction boundary.

### 3.4 What is **not** superseded

- `WAVE0_01_SCHEMA.sql` — unchanged. `core-api`'s schema is `core-api`'s schema; the
  simulator's own DDL lives in `WAVE0_05` and never touches `WAVE0_01`.
- `WAVE0_02_OPENAPI.yaml` — unchanged. The outbound PSP API is a *new* surface (`PSP-`
  IDs in `WAVE0_05`); `API-008..013` describe the operator-facing surface in `core-api`
  which is unaffected by direction-of-call decisions inside `core-api`.
- `WAVE0_03_WEBHOOK_PSP_CONTRACT.md` requirements **WHK-001..015** — unchanged in
  substance. `payments-sim` is now the named source of these events (always implied,
  now built); the inbound envelope, idempotency model, transition table, per-line
  allocation, and security boundary all stand.
- `WAVE0_04` **SCF-001 / SCF-002 / SCF-004** — unchanged. (SCF-002's "JDK 17 / JRE 17"
  wording is a pre-existing discrepancy with the actual Java 21 stack; that is **out of
  scope for RX-001** and is flagged separately for a future `RX-`.)
- All Stage 1 verification records, including SCF-003's. They record history; they are
  not edited.

---

## 4. Introduces — `SCF-005` (born here, not by editing `WAVE0_04`)

| ID | Requirement | Acceptance criteria | Depends-on |
|----|-------------|---------------------|------------|
| SCF-005 | `payments-sim` ships as a second Spring Boot service in `docker-compose`, with its **own** Postgres instance; both databases are health-gated and the end-to-end smoke test waits on both `service_healthy`. Datasources are configured via environment (mirroring how `core-api` does it under SCF-003); neither service ever points at the other's database. | `docker compose up` brings up four services — `db` (core-api's Postgres), `payments-sim-db` (the simulator's Postgres), `core-api`, `payments-sim` — all reaching `(healthy)`. `payments-sim` `depends_on` its own DB `service_healthy`; `core-api` `depends_on` its own DB `service_healthy`. Concrete compose/health surface is detailed in `WAVE0_05 §7`. | SCF-003 |

Notes:

- SCF-005 is born **FROZEN** as part of this RX (see Freeze Ledger §1b row). Frozen ≠
  done; the implementation lands when Feature 2 compose work happens and the verification
  log in `WAVE0_05 §9` records the proof.
- Naming the simulator's DB service `payments-sim-db` (vs. reusing `db`) is fixed here so
  no accidental aliasing makes the two instances look like one. Concrete compose YAML
  belongs in `WAVE0_05 §7`, not here.
- The integration-owner role described in `WAVE0_00 §6` still owns compose; SCF-005 is a
  spec for them to implement, not a new owner.

---

## 5. Forward pointer

`WAVE0_05_PSP_API.md` carries the **`PSP-`** ID family for everything D1/D2/D3 imply at
the level of an enforceable seam:

- Outbound API endpoints (`core-api → payments-sim`): create link, request capture,
  request cancellation, request refund. Request/response DTOs and the
  stores-never-mints / mints-on-event split that pairs with `WAVE0_03 §2`.
- The tx-ordering invariant from D3, stated as a hard rule on the outbound seam, with
  the named flag that `PaymentService` is currently class-level `@Transactional` and the
  outbound wiring task must restructure so the HTTP call sits outside the tx.
- Fail-loud / no-retry semantics; deferral of outbound idempotency + retry.
- `payments-sim` internal schema slice (D2): reference ledger row, refund row, event
  sequence record. This is contract DDL in the artifact, not a committed migration file.
- Checkout simulation trigger (D1): the shape of "simulate guest authorises this link"
  and how it drives the `AUTHORISATION` webhook (WHK-006). `pay-web` deferred.
- WHK-015 sync test seam — concrete shape (the `WAVE0_03 §6a` choice between
  `?sync=true` and a dedicated endpoint is pinned here, restating the `CLAUDE.md` rule
  that the seam is test-only and unreachable in the running system).
- Compose & health detail for SCF-005 (defined here, detailed there).
- Accountability block + empty verification log + changelog.

`WAVE0_05` is **DRAFT** until human sign-off; RX-001 does not self-freeze it.

---

## 6. Does NOT touch (guards)

These are guards against the same class of mistake that motivated this whole mechanism.
Treat them as hard fences.

- **Does not edit any verification log in any artifact.** Stage 1 verification entries
  for SCF-003 remain verbatim — they describe what shipped.
- **Does not edit the text or acceptance criteria of any frozen `SCH-`/`ENM-`/`API-`/
  `WHK-`/`SCF-` requirement.** Only pointer banners are added beside superseded
  statements; banners are pointer-only.
- **Does not modify `V1__wave0_schema.sql` or introduce a `V2`** for `core-api`. The
  simulator's DDL is `payments-sim`'s, governed by `PSP-` IDs in `WAVE0_05`, persisted
  through `payments-sim`'s own Flyway when Feature 2 builds — never co-mingled with
  `core-api`'s migrations.
- **Does not add a `pay-web` directory, service, or compose entry.** D1 defers it.
- **Does not self-freeze `WAVE0_05`** or any of its forthcoming `PSP-` IDs. Freezing is
  the arbiter's act; the Freeze Ledger records it.
- **Does not change `WHK-001..015` substance.** `payments-sim` becomes the *named* event
  source (always implied, now built); envelope, transitions, allocation, and idempotency
  rules stand.
- **Does not "fix" the pre-existing SCF-002 `JDK/JRE 17` wording.** Java 21 vs. 17 is a
  separate pre-existing discrepancy flagged for a future `RX-` — out of scope here.

---

## 7. Accountability

| Field | Value |
|-------|-------|
| Owner | Desk (arbiter) |
| Status | `FROZEN` on commit of this file (authoritative in `WAVE0_00 §1b`). |
| Sign-off | ☑ frozen as RX-001 — supersedence is now in force; revision requires `RX-002`. |
| Consumers | `payments-sim` (Feature 2 builder), `core-api` payment orchestration (outbound wiring), integration owner (compose), every Wave 0 reader (must check §1b `Superseded-by`). |

### 7a. Verification log

*(Empty until Feature 2 build. Per ID below: what was built, commit/PR, the test that proves it.)*

| ID | Built | Commit/PR | Proving test |
|----|-------|-----------|--------------|
| SCF-005 | — | — | — |

---

## 8. Changelog

| Version | Date | Change |
|---------|------|--------|
| 0.1 | 2026-06-12 | Initial draft & freeze of RX-001 — records D1 (no `pay-web` in Feature 2), D2 (`payments-sim` stateful with its own Postgres), D3 (fail-loud / no-retry outbound + tx-ordering invariant). Names every superseded statement with verbatim quotes; introduces SCF-005; forward-points to `WAVE0_05`. |
