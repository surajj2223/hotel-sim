# WAVE0_05 — PSP Outbound API & `payments-sim` Internal Schema

> **What this freezes (on sign-off).** Everything `core-api` and `payments-sim` need to
> talk to each other in Feature 2: the outbound API surface (`core-api → payments-sim`),
> the transaction-ordering invariant + failure semantics on that seam, the simulator's own
> minimal persistence schema, the checkout-simulation trigger that replaces a built
> `pay-web`, the concrete shape of the WHK-015 sync test seam, and the compose/health
> detail for SCF-005 (two Postgres instances, two service-healthy gates).
>
> **Status:** `DRAFT` (authoritative in `WAVE0_00 §1b` Freeze Ledger). Freezing requires
> arbiter sign-off.
> **Owner / arbiter:** Desk.
> **Depends on:**
> - `WAVE0_01_SCHEMA.sql` — `ENM-004..010` (referenced, never redefined).
> - `WAVE0_02_OPENAPI.yaml` Stage 2 (`API-008..013`) — the operator-facing surface that
>   triggers PSP calls; `WAVE0_05` is the downstream seam those endpoints invoke.
> - `WAVE0_03_WEBHOOK_PSP_CONTRACT.md` (`WHK-001..015`) — the **inbound** webhook contract,
>   unchanged. `WAVE0_05` is the matched outbound half; reference formats live in
>   `WAVE0_03 §2` and are cited here, never restated as new truth.
> - `WAVE0_04_SCAFFOLD.md` (`SCF-001..004`) + RX-001 `SCF-005` — compose footprint.
> - RX-001 (`refactor-x/RX-001-psp-direction-and-statefulness.md`) — D1/D2/D3 decisions.

---

## 0. Conventions

- **Money is `BIGINT` minor units + 3-letter currency.** No floats. Anywhere.
- **Reference formats are owned by `WAVE0_03 §2`.** This artifact cites them; it does not
  redefine them. `paymentLinkId` = `PL-` + 16 base62; `pspReference` = `PSP-` + 16 base62;
  `shopperReference` / `merchantReference` are minted by `core-api`.
- **Enums are owned by `WAVE0_01_SCHEMA.sql`.** `ENM-005 PaymentStatus`,
  `ENM-006 RefundStatus`, `ENM-007 PspEventCode`, `ENM-010` reference taxonomy.
- **Stores-never-mints / mints-on-event split (WHK-001):** `payments-sim` *stores* the
  references `core-api` sends (`shopperReference`, `merchantReference`,
  `refundMerchantReference`) and *mints* `paymentLinkId` and `pspReference` on the events
  that produce them. This is identical to `WAVE0_03 §2`; restated only as a reminder of
  who owns what.

---

## 1. Requirements (`PSP-`)

| ID | Requirement | Acceptance criteria | Depends-on |
|----|-------------|---------------------|------------|
| PSP-001 | Outbound API — **create payment link**. `core-api` POSTs `merchantReference`, `shopperReference`, `amount`, `currency`, `captureMode` to `POST /v1/payment-links`. `payments-sim` validates, persists a reference-ledger row keyed by `merchantReference`, mints a `paymentLinkId` (`PL-…`), and returns it along with a `hostedUrl` for the checkout trigger. | `201` body matches §2.1; reference-ledger row exists with `status='PENDING'`; same `merchantReference` re-submitted returns `409` (no second link minted for the same anchor); response never invents `shopperReference`/`merchantReference`. | ENM-010, WHK-001 |
| PSP-002 | Outbound API — **request capture**. `core-api` POSTs `{ "amount": <≤ authorised> }` to `POST /v1/payments/{pspReference}/captures`. `payments-sim` validates against its own stored `amountAuthorised`, queues an asynchronous `CAPTURE` webhook with a stable `idempotencyKey` (§4.3), and returns `202`. `amount` MAY be `null` for a full capture; partial capture is in scope (`amount ≤ authorised`); multi-capture is **out** (second request `409`). | `202` with no final-state in body; a `CAPTURE` webhook arrives at the configured callback URL with the §3 envelope; `amount > authorised` → `422`; second capture against the same `pspReference` → `409`. | ENM-005, WHK-007, WHK-012, INV-005 |
| PSP-003 | Outbound API — **request cancellation**. `core-api` POSTs `POST /v1/payments/{pspReference}/cancellations`. `payments-sim` validates that the payment is `AUTHORISED` with `amountCaptured == 0`, queues a `CANCELLATION` webhook, returns `202`. | `202` with no final-state in body; `CANCELLATION` webhook arrives; cancel-after-capture → `409`. | ENM-005, WHK-008 |
| PSP-004 | Outbound API — **request refund**. `core-api` POSTs `{ "amount", "refundMerchantReference", "reason"? }` to `POST /v1/payments/{pspReference}/refunds`. `payments-sim` validates `amount ≤ captured − refunded`, persists a refund row, mints a **distinct** refund `pspReference` (`PSP-…`) linked to the parent via `originalReference`, queues a `REFUND` webhook (carrying `originalReference` + `refundMerchantReference` per `WAVE0_03 §3`), returns `202` with the minted refund `pspReference`. | `202` body includes refund `pspReference` distinct from parent's; refund row persisted (`PENDING`); webhook arrives with `originalReference = <parent pspReference>` and `refundMerchantReference` echoed; over-refund → `422`; duplicate `refundMerchantReference` → `409`. | ENM-006, WHK-009, WHK-001 |
| PSP-005 | **Stores-never-mints / mints-on-event split.** `payments-sim` stores `shopperReference`, `merchantReference`, `refundMerchantReference` exactly as received and echoes them in every response and webhook. It mints `paymentLinkId` at link creation (PSP-001) and `pspReference` at `AUTHORISATION` and at `REFUND` (a distinct one). Formats per `WAVE0_03 §2`. | Round-trip test: every reference `core-api` sent appears unchanged in `payments-sim`'s stored row and in the webhook envelope; minted references match the `PL-` / `PSP-` + 16 base62 grammar. | WHK-001, ENM-010 |
| PSP-006 | **Transaction-ordering invariant (D3) on the outbound seam.** `core-api` MUST `validate-and-persist (PENDING) → commit → call payments-sim → stamp PSP response in a new transaction`. The HTTP call MUST NOT happen inside an open DB transaction. The current class-level `@Transactional` on `PaymentService` (`core-api/src/main/java/com/hotelops/core/payment/PaymentService.java`) is incompatible with this rule and MUST be restructured during the Part-2 outbound wiring task — the request side commits before the HTTP call; the response is stamped through a separate public bean method, mirroring the GAP-2 outbox split. | The outbound wiring's main path uses two `@Transactional` boundaries with the HTTP call between them; a test runs the outbound call against a stopped `payments-sim` and observes the DB row remains `PENDING` (no in-flight tx held against the connection pool). | (none in 01..04; new) |
| PSP-007 | **Fail-loud / no-retry failure semantics (D3).** Any outbound failure — connection refused, read timeout, 5xx, malformed body, signature mint failure — surfaces as a `502`-class `ApiError` to the operator; the payment row stays in its pre-call state; no auto-retry. Retry is operator-initiated by re-issuing the original operator action, which mints a **new** `merchantReference` (so the next attempt is a distinct anchor and cannot accidentally double-act on the prior one). | `payments-sim` down → operator endpoint returns 502, payment row unchanged (`PENDING`), no entries in `payments-sim` reference ledger; operator re-issues `POST /payments` → new `merchantReference`; both rows are distinct. | API-008..012 |
| PSP-008 | **Deferred (explicitly).** Outbound idempotency tokens, retry policy, circuit-breaker, and dead-letter queue are out of POC scope, in the same register as holds/drafts and multi-capture. Stated here so a later task does not "improve" them in without a contract. | This row exists in §1 and is cited if a future task proposes adding any of the deferred items. | — |
| PSP-009 | **`payments-sim` internal schema — reference ledger row.** A single table (`psp_payment`) keyed by `merchant_reference` (UNIQUE) carries `psp_reference` (UNIQUE, nullable until AUTHORISATION), `payment_link_id` (UNIQUE, set at PSP-001), `shopper_reference`, `amount_requested`, `amount_authorised`, `amount_captured`, `amount_refunded`, `currency`, `status` (`ENM-005`), `created_at`, `updated_at`. Money columns are `BIGINT`. | DDL in §4.1 applies to a fresh `payments-sim-db`; constraints enforced; a refund that would push `amount_refunded > amount_captured` fails at the DB constraint level as well as the application check. | WHK-001, ENM-005, ENM-010 |
| PSP-010 | **`payments-sim` internal schema — refund row.** A `psp_refund` table keyed by `refund_merchant_reference` (UNIQUE) carries `psp_reference` (UNIQUE, distinct from the parent's), `original_reference` (FK to parent `psp_payment.psp_reference`), `amount`, `currency`, `status` (`ENM-006`), `reason`, `created_at`. | DDL in §4.2 applies; FK enforced; same `refund_merchant_reference` twice → DB UNIQUE violation surfaced as `409`. | PSP-009, WHK-009, ENM-006 |
| PSP-011 | **`payments-sim` internal schema — event sequence record.** A `psp_event_sequence` table keyed by `(psp_reference, event_code)` with a monotonic `seq INT` counter; the `idempotencyKey` for every emitted webhook is computed deterministically as `<psp_reference>:<event_code>:<seq>` per `WHK-003`. A redelivery emits the **same** sequence, not a new one. | Two redeliveries of the same logical event carry identical `idempotencyKey`; a manual replay through the admin/test trigger reuses the seq. | WHK-003, SCH-071 |
| PSP-012 | **`payments-sim` owns its own Flyway** under `payments-sim/src/main/resources/db/migration/`. Its datasource points at `payments-sim-db` via `SPRING_DATASOURCE_URL`. **It never points at `core-api`'s database.** `core-api` never points at `payments-sim-db`. | A migration applied to `payments-sim-db` does not appear in `core-api`'s `flyway_schema_history`; misconfigured datasource (pointed at the wrong DB) fails fast on startup with a recognisable error. | RX-001 §2 D2, SCF-005 |
| PSP-013 | **Checkout-simulation trigger (D1)** — `payments-sim` exposes `POST /v1/test/payment-links/{paymentLinkId}/authorise` (operator/test-only; never wired from `core-api`) that flips the link's row to `AUTHORISED`, mints the `pspReference`, sets `amountAuthorised = amountRequested` (or the supplied override), and emits the `AUTHORISATION` webhook (WHK-006). | Calling the trigger on a `PENDING` link causes a single `AUTHORISATION` webhook to fire at the configured callback; the `psp_payment` row transitions to `AUTHORISED` with the minted `pspReference`. Trigger called twice on the same link → second call `409` (single auth per link). | WHK-006, RX-001 §2 D1 |
| PSP-014 | **`pay-web` deferred.** No `pay-web` directory, service, or compose entry is added. Future cosmetic upgrade replacing the human-facing surface of PSP-013; the webhook seam is unchanged. | No `pay-web` symbol/import/path exists in the repo. The trigger (PSP-013) is the only way a non-test operator drives an `AUTHORISATION` in Feature 2. | RX-001 §2 D1 |
| PSP-015 | **WHK-015 concrete sync seam (test-only).** `payments-sim` exposes `POST /v1/test/payment-links/{paymentLinkId}/authorise?sync=true` (and the analogous flag on the capture/cancel/refund admin triggers — see §6) that delivers the webhook **synchronously** (the HTTP call returns after `core-api` has processed the callback). The seam is registered as a `@Profile("test")` bean or external test harness — **unreachable** when `payments-sim` runs in its non-test profile. Async (`sync=false` or omitted) is the production path. | A smoke test executes `capture → trigger(sync=true) → assert CAPTURED + per-line postings` with no sleeps; starting `payments-sim` without the test profile makes the `sync=true` query fail (`404` or feature-flag rejection) so the production path stays async. The seam is restated as test-only per the `CLAUDE.md` rule. | WHK-015 |
| PSP-016 | **Webhook delivery — outbound from `payments-sim` to `core-api`.** Every emitted webhook is a `POST` to `core-api`'s receiver (`API-013`) with the `WAVE0_03 §3` envelope and the `X-PSP-Signature` HMAC header (`WHK-014`). The callback URL is configurable via environment (`CORE_API_WEBHOOK_URL`) so compose can wire it without hard-coding. A non-2xx response from `core-api` does not retry in the POC (paired with PSP-008's retry deferral); the failure is logged in `payments-sim` for operator inspection. | Webhook hits the configured URL; signature header validates against `core-api`'s shared secret; `4xx` response is logged but not retried; envelope shape passes a contract test against `WAVE0_03 §3`. | WHK-002, WHK-014 |
| PSP-017 | **SCF-005 compose surface (detail).** `docker-compose.yml` gains two services: `payments-sim-db` (postgres:16-alpine, distinct named volume, `pg_isready` healthcheck) and `payments-sim` (built from `payments-sim/Dockerfile`, `depends_on` `payments-sim-db` `service_healthy`, `8081:8081`, healthcheck on `/actuator/health`). `core-api` `depends_on` continues to gate only on its own `db`; the end-to-end smoke test waits on **both** DBs (`db` and `payments-sim-db`) healthy *and* both apps (`core-api`, `payments-sim`) healthy before issuing the first request. `payments-sim` is configured with `CORE_API_WEBHOOK_URL=http://core-api:8080/v1/payments/webhooks` (or the URL pinned by `API-013`). | `docker compose ps` shows all four services `(healthy)`; `docker compose config` validates; the smoke test does not race against an unready DB on either side. | SCF-005, RX-001 §4 |

---

## 2. Outbound API — request/response DTOs

All endpoints below are `payments-sim → core-api` is **never the direction**; the
direction is **always** `core-api → payments-sim`. Inbound webhooks (the other direction)
remain governed by `WAVE0_03`.

Common request headers:

- `X-PSP-Api-Key: <shared POC secret>` — coarse mutual-auth between the two services
  (separate from the webhook `X-PSP-Signature`).
- `Content-Type: application/json`.
- `Idempotency-Key` — **not required** in the POC (PSP-008 defers retry). The
  *anchor* against duplicates is the operator-minted `merchantReference` /
  `refundMerchantReference`, which `payments-sim` enforces UNIQUE per PSP-009/PSP-010.

Common error envelope mirrors `core-api`'s `ApiError` (`API-007`) so the operator-facing
`502` from `core-api` can include the underlying `payments-sim` reason verbatim.

### 2.1 `POST /v1/payment-links` (PSP-001)

Request:
```json
{
  "merchantReference": "MR-<...>",
  "shopperReference":  "SHPR-<...>",
  "amount":            70000,
  "currency":          "GBP",
  "captureMode":       "MANUAL",
  "callbackUrl":       "http://core-api:8080/v1/payments/webhooks"
}
```
`callbackUrl` is optional; if omitted, `payments-sim` uses its `CORE_API_WEBHOOK_URL`
default (PSP-016). It is part of the request only to make the contract self-contained;
the POC compose pins one value.

Response `201`:
```json
{
  "paymentLinkId":     "PL-<16 base62>",
  "merchantReference": "MR-<...>",
  "shopperReference":  "SHPR-<...>",
  "amount":            70000,
  "currency":          "GBP",
  "status":            "PENDING",
  "hostedUrl":         "http://payments-sim:8081/checkout/<paymentLinkId>"
}
```

The `hostedUrl` exists so the operator (or a later `pay-web`) has a single URL to
present; in the POC the `POST /v1/test/.../authorise` trigger (PSP-013) does the work
that a human pressing "Pay" on `hostedUrl` would do.

### 2.2 `POST /v1/payments/{pspReference}/captures` (PSP-002)

Request:
```json
{ "amount": 54000 }      // null/omitted → full capture (= amountAuthorised)
```

Response `202`:
```json
{
  "pspReference":      "PSP-<...>",
  "merchantReference": "MR-<...>",
  "amount":            54000,
  "status":            "PENDING_CAPTURE"
}
```

`status: "PENDING_CAPTURE"` is a **wire-only acknowledgement** that the capture has been
queued for asynchronous emission; it is **not** an `ENM-005 PaymentStatus` value and is
never persisted by `core-api`. The authoritative status flip lands on the `CAPTURE`
webhook (`WHK-007`).

### 2.3 `POST /v1/payments/{pspReference}/cancellations` (PSP-003)

Request: empty body.

Response `202`:
```json
{
  "pspReference":      "PSP-<...>",
  "merchantReference": "MR-<...>",
  "status":            "PENDING_CANCELLATION"
}
```

### 2.4 `POST /v1/payments/{pspReference}/refunds` (PSP-004)

Request:
```json
{
  "amount":                   6000,
  "refundMerchantReference":  "MR-RF-<...>",
  "reason":                   "guest complaint"        // optional
}
```

Response `202`:
```json
{
  "pspReference":             "PSP-<distinct from parent>",
  "originalReference":        "PSP-<parent>",
  "refundMerchantReference":  "MR-RF-<...>",
  "amount":                   6000,
  "currency":                 "GBP",
  "status":                   "PENDING_REFUND"
}
```

### 2.5 Error mapping (per-endpoint)

| Status | When | Body |
|--------|------|------|
| `400` | malformed JSON, missing required field | `ApiError` |
| `401` | bad/missing `X-PSP-Api-Key` | `ApiError` |
| `404` | `pspReference` not known to `payments-sim` | `ApiError` |
| `409` | duplicate `merchantReference` (PSP-001), second capture (PSP-002), cancel-after-capture (PSP-003), duplicate `refundMerchantReference` (PSP-004) | `ApiError` with current state |
| `422` | amount > authorised (PSP-002) or > capturable (PSP-004) | `ApiError` with current state |
| `5xx` | simulator internal | `ApiError` |

`core-api`'s side maps any non-`2xx` (including socket / read-timeout) into the operator
`502` per PSP-007.

---

## 3. Transaction ordering & failure semantics on the outbound seam

### 3.1 The invariant (PSP-006)

```
[tx1] open transaction
        validate (revalidate write-time state per INV-003)
        persist Payment row in PENDING
      commit tx1
[net] HTTP POST to payments-sim
[tx2] open transaction
        stamp paymentLinkId / pspReference / amountAuthorised from PSP response
      commit tx2
```

The HTTP call **never** sits inside an open transaction. Three concrete consequences for
the implementing task:

1. **`PaymentService` restructure required.** The class is currently declared
   `@Transactional` at class level (`core-api/src/main/java/com/hotelops/core/payment/PaymentService.java:41-42`).
   When the outbound wiring lands, the method that drives the PSP call must NOT be a
   transactional method of `PaymentService`. The split mirrors the GAP-2 fix: tx1 lives
   on the existing service; the HTTP call lives on an orchestrator (controller-level
   service or a dedicated `PspGateway`-style bean) that calls back into a *separate*
   public, proxied `@Transactional` method on a separate bean to stamp the response. The
   Stage 2 verification log records the proof for this exact restructure.
2. **No `@Transactional(propagation = REQUIRES_NEW)` shortcut.** That keeps tx1 logically
   open in the calling thread; the connection-pool problem stands. A clean caller-side
   split is required.
3. **Idempotency under crash-between-tx1-and-tx2.** If `core-api` crashes after tx1 commits
   but before tx2 stamps the PSP response, the payment row stays `PENDING`. The recovery
   path is operator-driven: re-issue the operator action; tx1 mints a *new* `merchantReference`
   (PSP-007). `payments-sim`'s UNIQUE constraint on `merchant_reference` (PSP-009) makes
   double-action against the same anchor impossible by construction.

### 3.2 Fail-loud / no-retry (PSP-007)

The mapping `core-api` applies on any outbound failure:

| Outbound condition | `core-api` operator response |
|--------------------|------------------------------|
| connection refused / read timeout | `502 ApiError("PSP unreachable")` |
| `payments-sim` 4xx (`401`/`404`/`409`/`422`) | `502 ApiError("PSP rejected: <code> <body.message>")` — operator sees underlying reason |
| `payments-sim` 5xx | `502 ApiError("PSP error")` |
| malformed JSON / unknown body shape | `502 ApiError("PSP malformed response")` |

In all cases the payment row stays exactly in its pre-call state. No partial PSP stamp,
no fallback status, no retry tick, no scheduled retry. The operator's recovery is to
re-issue the *operator* action; a fresh `merchantReference` makes that safe.

PSP-008 explicitly defers outbound idempotency tokens, automatic retry, circuit-breaker,
and dead-letter queues out of POC scope.

---

## 4. `payments-sim` internal schema (D2)

This is the simulator's own DDL. It lives in `payments-sim/src/main/resources/db/migration/`
and runs against the **separate** `payments-sim-db` instance defined by SCF-005 /
PSP-017. It is **never** co-mingled with `core-api`'s `V1__wave0_schema.sql`. The DDL
below is *contract DDL* in this artifact — a committed Flyway migration lands when
Feature 2 builds.

### 4.1 `psp_payment` (PSP-009)

```sql
CREATE TYPE psp_payment_status AS ENUM (              -- mirrors ENM-005 PaymentStatus subset
  'PENDING', 'AUTHORISED', 'CAPTURED', 'CAPTURE_FAILED',
  'CANCELLED', 'AUTH_EXPIRED', 'PARTIALLY_REFUNDED', 'REFUNDED', 'FAILED'
);

CREATE TABLE psp_payment (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  merchant_reference  TEXT NOT NULL UNIQUE,           -- core-api-minted; PSP stores, never invents
  shopper_reference   TEXT NOT NULL,
  payment_link_id     TEXT NOT NULL UNIQUE,           -- PSP-minted at PSP-001
  psp_reference       TEXT UNIQUE,                    -- PSP-minted at AUTHORISATION (PSP-013)
  amount_requested    BIGINT NOT NULL CHECK (amount_requested  >  0),
  amount_authorised   BIGINT NOT NULL DEFAULT 0 CHECK (amount_authorised  >= 0),
  amount_captured     BIGINT NOT NULL DEFAULT 0 CHECK (amount_captured    >= 0),
  amount_refunded     BIGINT NOT NULL DEFAULT 0 CHECK (amount_refunded    >= 0),
  currency            CHAR(3) NOT NULL,
  status              psp_payment_status NOT NULL DEFAULT 'PENDING',
  capture_mode        TEXT NOT NULL,                  -- mirrors ENM-004 CaptureMode
  callback_url        TEXT NOT NULL,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_captured_le_authorised CHECK (amount_captured <= amount_authorised),
  CONSTRAINT chk_refunded_le_captured   CHECK (amount_refunded <= amount_captured)
);

CREATE INDEX idx_psp_payment_psp_reference ON psp_payment (psp_reference);
```

### 4.2 `psp_refund` (PSP-010)

```sql
CREATE TYPE psp_refund_status AS ENUM (               -- mirrors ENM-006 RefundStatus
  'PENDING', 'REFUNDED', 'REFUND_FAILED'
);

CREATE TABLE psp_refund (
  id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  refund_merchant_reference   TEXT NOT NULL UNIQUE,    -- core-api-minted
  psp_reference               TEXT NOT NULL UNIQUE,    -- PSP-minted; distinct from parent's
  original_reference          TEXT NOT NULL REFERENCES psp_payment (psp_reference),
  amount                      BIGINT NOT NULL CHECK (amount > 0),
  currency                    CHAR(3) NOT NULL,
  status                      psp_refund_status NOT NULL DEFAULT 'PENDING',
  reason                      TEXT,
  created_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_psp_refund_original_reference ON psp_refund (original_reference);
```

### 4.3 `psp_event_sequence` (PSP-011)

```sql
CREATE TABLE psp_event_sequence (
  psp_reference  TEXT NOT NULL,
  event_code     TEXT NOT NULL,                       -- mirrors ENM-007 PspEventCode
  seq            INT  NOT NULL DEFAULT 1,
  last_emitted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (psp_reference, event_code)
);
```

The deterministic key emitted to `core-api` per `WHK-003`:

```
idempotencyKey = psp_reference || ':' || event_code || ':' || seq
```

A redelivery (same logical event re-sent because `core-api` returned a non-2xx, or because
the sync seam re-runs it) reuses the row — `seq` is **not** incremented. A genuinely new
event (e.g. a second admin trigger after a first failed delivery cleared) does increment
`seq` and produces a new key. The deterministic seq+redelivery semantics are how
`WHK-005` inbound dedupe stays correct against the same outbound emitter.

> **Notes on the schema slice.** Two intentional restraints: (a) no JSONB columns, no
> generic event log — the simulator is small; (b) no schema for users/auth — the
> `X-PSP-Api-Key` is config-level, not a row. If a later requirement needs either, add a
> row to §1 first.

---

## 5. Checkout-simulation trigger (D1)

Replaces a built `pay-web`. The trigger is the operator/test-facing surface that drives
the `AUTHORISATION` step a human would otherwise drive by paying on `pay-web`.

### 5.1 `POST /v1/test/payment-links/{paymentLinkId}/authorise` (PSP-013)

Request (all fields optional):
```json
{ "amount": 70000 }       // overrides amountAuthorised; default = psp_payment.amount_requested
```

Behaviour:

1. Look up `psp_payment` by `payment_link_id`. `404` if unknown.
2. Reject if `status != PENDING` (`409`); single-auth per link.
3. Mint `psp_reference` (`PSP-` + 16 base62); set `amount_authorised`, transition
   `status → AUTHORISED`, stamp `psp_event_sequence` row.
4. Emit the `AUTHORISATION` webhook with the `WAVE0_03 §3` envelope to `callback_url`.

Response `202` (async) or `200` (sync via `?sync=true` — see §6 PSP-015):
```json
{
  "paymentLinkId":     "PL-...",
  "pspReference":      "PSP-<minted>",
  "amountAuthorised":  70000,
  "status":            "AUTHORISED"
}
```

Why this is here and not in `WAVE0_03`: it is part of the simulator's **API**, not the
webhook event vocabulary. The webhook the trigger produces is the same WHK-006
`AUTHORISATION` event — `core-api`'s receiver does not know or care that an admin trigger
fired it.

### 5.2 Companion test triggers for capture / cancel / refund

To keep the test seam regular, three admin endpoints mirror PSP-013 for the events that
PSP-002/003/004 *request* but `payments-sim` *emits* asynchronously:

- `POST /v1/test/payments/{pspReference}/capture?sync={true|false}` — drives the
  pre-queued `CAPTURE` webhook (`WHK-007`).
- `POST /v1/test/payments/{pspReference}/cancel?sync={true|false}` — drives `CANCELLATION`.
- `POST /v1/test/refunds/{refundMerchantReference}/settle?sync={true|false}` — drives `REFUND`.

These are *only* webhook drivers — they do not bypass PSP-002/003/004's request side; the
operator path still goes through `core-api` → PSP-002 → queue. The triggers exist so a
test can deterministically wait for the queued webhook without sleeping.

> Note on production-safety: every endpoint under `/v1/test/...` is profile-gated (§6
> PSP-015). Outside the test profile they return `404`.

---

## 6. WHK-015 concrete sync seam (PSP-015)

`WAVE0_03 §6a` decided that an async-by-default integration needs a synchronous-in-test
seam, but left the shape open. Pinned shape:

- The seam is a `?sync=true` query parameter on **every** `/v1/test/...` admin trigger
  (PSP-013 + the three companions in §5.2). Default is `sync=false` (async — the
  webhook is queued and fired on a background dispatcher, just like production).
- `sync=true` makes the trigger call `core-api`'s webhook receiver **inline** and block
  until that call returns 2xx. The trigger's own HTTP response then carries `200` (not
  `202`) so the test can assert against `core-api`'s persisted state immediately.
- The admin trigger router is registered as a `@Profile("test")` bean (or behind a
  feature flag toggled by a test-only env var). Outside the test profile, `/v1/test/...`
  returns `404` — i.e. the **seam is unreachable in the running system** per `CLAUDE.md`.

Restated from `CLAUDE.md` (authoritative): *the `payments-sim` synchronous webhook seam
(WHK-015) is test-only. It must be unreachable in the running system — test-profile bean
or external harness, never a prod code path.*

> Why `?sync=true` on the admin trigger rather than a dedicated endpoint: the trigger is
> already the test-only surface; bolting sync on it keeps the production path
> (the dispatcher) the **same** code regardless of test mode. A dedicated sync endpoint
> would duplicate the dispatcher and risk drift. Rejected.

---

## 7. Compose & health surface (SCF-005 detail; PSP-017)

This section is the detail SCF-005 (defined in RX-001 §4) points to. It does not edit
`SCF-003`'s record — `SCF-003`'s verification log describes the single-Postgres compose
that shipped; what follows is the forward spec for the *augmented* compose Feature 2 ships.

### 7.1 Services after Feature 2

| Service           | Image / build                  | Port | Healthcheck                                                          | depends_on                          |
|-------------------|--------------------------------|------|----------------------------------------------------------------------|-------------------------------------|
| `db`              | `postgres:16-alpine`           | 5432 | `pg_isready -U hotelops -d hotelops`                                  | —                                   |
| `payments-sim-db` | `postgres:16-alpine`           | 5433 | `pg_isready -U pspsim   -d pspsim`                                    | —                                   |
| `core-api`        | `./core-api/Dockerfile`        | 8080 | `curl -fsS http://localhost:8080/actuator/health \| grep -q 'UP'`     | `db: service_healthy`               |
| `payments-sim`    | `./payments-sim/Dockerfile`    | 8081 | `curl -fsS http://localhost:8081/actuator/health \| grep -q 'UP'`     | `payments-sim-db: service_healthy`  |

Pinned naming choices (so the implementing task does not have to re-decide):

- DB service names: **`db`** (existing) and **`payments-sim-db`** (new — explicit, not
  `db2`, so no aliasing with `db`).
- Volumes: existing `hotelops-db-data`; new `payments-sim-db-data`.
- Sim DB credentials: `pspsim` / `pspsim` / `pspsim` (DB / user / password), mirroring
  the `core-api` POC pattern but distinct so cross-wiring is loud.
- Sim host port: `5433` (avoid collision with `5432`).
- App port: `8081`.

### 7.2 Environment

`payments-sim`:

| Env var | Value (compose) | Purpose |
|---------|-----------------|---------|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://payments-sim-db:5432/pspsim` | datasource (PSP-012) |
| `SPRING_DATASOURCE_USERNAME` | `pspsim` | |
| `SPRING_DATASOURCE_PASSWORD` | `pspsim` | |
| `CORE_API_WEBHOOK_URL` | `http://core-api:8080/v1/payments/webhooks` | PSP-016 |
| `PSP_WEBHOOK_SECRET` | `<POC shared secret>` | WHK-014 HMAC |
| `SPRING_PROFILES_ACTIVE` | (unset in prod compose; `test` only in test compose) | gates `/v1/test/...` |

`core-api` (added under SCF-005):

| Env var | Value (compose) | Purpose |
|---------|-----------------|---------|
| `PAYMENTS_SIM_BASE_URL` | `http://payments-sim:8081` | outbound calls (PSP-001..004) |
| `PSP_API_KEY` | `<POC shared secret>` | `X-PSP-Api-Key` on outbound calls |
| `PSP_WEBHOOK_SECRET` | same as `payments-sim` | inbound HMAC verification (WHK-014) |

### 7.3 Smoke test gating

The end-to-end smoke test (owned by the integration owner per `WAVE0_00 §6`) waits on:

```
db: service_healthy            AND
payments-sim-db: service_healthy AND
core-api: service_healthy       AND
payments-sim: service_healthy
```

…before issuing its first request. Per WHK-015 / PSP-015, it uses the sync seam to
assert final state and per-line postings deterministically, without sleeps.

### 7.4 Non-compose runs (unchanged from SCF-003)

`payments-sim`'s `application.yml` keeps a `localhost:5433` datasource default so the
service runs against a locally reachable Postgres without compose env overrides — same
pattern as `core-api` does for SCF-003. Compose overrides only host (and the URL/key
config) via environment.

---

## 8. Cross-references summary

| Concept | Source of truth | Cited here |
|---------|-----------------|------------|
| PSP reference formats (`PL-…`, `PSP-…`) | `WAVE0_03 §2` | PSP-005 |
| Webhook envelope shape | `WAVE0_03 §3` | PSP-016 |
| Inbound event vocabulary + state transitions | `WAVE0_03 §1, §4` | PSP-002/003/004/013 |
| Per-line allocation (capture/refund) | `WAVE0_03 §5` (WHK-007/009/012) | only referenced — never restated |
| `idempotencyKey` derivation | `WAVE0_03 §3, §6` (WHK-003, WHK-005) | PSP-011 |
| Reference taxonomy | `WAVE0_01_SCHEMA.sql` `ENM-010` | PSP-005, PSP-009/010 |
| `PaymentStatus` / `RefundStatus` / `PspEventCode` | `WAVE0_01_SCHEMA.sql` `ENM-005/006/007` | PSP-009/010/011 |
| Operator-facing trigger surface (`X-Human-Auth`) | `WAVE0_02 §API-008..012` + INV-007 | PSP-007 (operator side; not redefined) |
| Compose / health-check pattern | `WAVE0_04 §2, §3` (SCF-003 verification log) | PSP-017 |
| RX-001 D1/D2/D3 decisions | `refactor-x/RX-001-…` | §3, §4, §5, §6, §7 |

Nothing in this artifact redefines a frozen ID. Where a frozen ID is referenced, the
citation is one of the rows above.

---

## 9. Accountability

| Field | Value |
|-------|-------|
| Owner | Desk (arbiter) |
| Status | `DRAFT` (authoritative in `WAVE0_00 §1b`). Next: `FROZEN` on arbiter sign-off; `IN-BUILD` when Feature 2 code starts. |
| Sign-off | ☐ pending. |
| Consumers | `payments-sim` (Feature 2 builder), `core-api` payment orchestration (outbound wiring), integration owner (compose). |

### 9a. Verification log

*(Empty until Feature 2 build. Per ID: what was built, commit/PR, the test that proves it.)*

| ID | Built | Commit/PR | Proving test |
|----|-------|-----------|--------------|
| PSP-001..017 | — | — | — |

---

## 10. Changelog

| Version | Date | Change |
|---------|------|--------|
| 0.1 | 2026-06-12 | Initial draft. Outbound PSP API (PSP-001..005), tx-ordering + fail-loud semantics (PSP-006..008), `payments-sim` internal schema (PSP-009..012), checkout-sim trigger replacing `pay-web` (PSP-013/014), WHK-015 concrete sync seam (PSP-015), webhook delivery (PSP-016), SCF-005 compose detail (PSP-017). Drafted against state amended by RX-001; no `WHK-`/`SCH-`/`ENM-` IDs redefined. DRAFT in Freeze Ledger; pending sign-off. |
